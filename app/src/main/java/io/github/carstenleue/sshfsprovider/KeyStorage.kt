package io.github.carstenleue.sshfsprovider

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Stores SSH config and private keys encrypted on-device using
 * [EncryptedFile] (AES-256-GCM via Tink + Android Keystore master key).
 *
 * Thread-safety:
 * - [saveConfig] and [savePrivateKey] are `@Synchronized` to serialise the
 *   delete-then-create pattern required by [EncryptedFile] (overwrite not supported).
 * - [loadConfig] uses an in-memory mtime cache so [SshDocumentsProvider.queryRoots]
 *   and [SshConnectionManager.createSession] – called on every file-system
 *   interaction – do not decrypt the config file on every invocation.  Only a
 *   file-modification-time change triggers a real disk read.
 * - [loadPrivateKey] is called once per new SSH session and is not cached (keys
 *   are small and sessions are infrequent).
 *
 * All methods must be called off the main thread.
 *
 * Layout inside [Context.getFilesDir]:
 *   secure/
 *     config.json.enc      – serialised [SshConfig] JSON
 *     keys/
 *       <alias>.key.enc    – raw private key bytes, one file per host alias
 */
class KeyStorage(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val secureDir = File(context.filesDir, "secure").also { it.mkdirs() }
    private val keysDir = File(secureDir, "keys").also { it.mkdirs() }
    private val configFile = File(secureDir, "config.json.enc")

    // In-memory config cache – avoids decrypting on every queryRoots() call.
    @Volatile private var configCache: SshConfig? = null
    @Volatile private var configFileMtime: Long = 0L

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    /**
     * Persist [config] encrypted to disk and update the in-memory cache.
     * Synchronized to prevent interleaving with another thread's delete+create.
     */
    @Synchronized
    fun saveConfig(config: SshConfig) {
        writeEncrypted(configFile, config.toJson().toByteArray(Charsets.UTF_8))
        // Update cache after successful write.
        configCache = config
        configFileMtime = configFile.lastModified()
    }

    /**
     * Load the SSH config.  Returns the in-memory cache when the file has not
     * changed on disk; decrypts only when the mtime differs.
     */
    fun loadConfig(): SshConfig? {
        if (!configFile.exists()) {
            configCache = null
            return null
        }
        val mtime = configFile.lastModified()
        if (mtime == configFileMtime) {
            configCache?.let { return it }
        }
        return try {
            val bytes = readEncrypted(configFile) ?: return null
            SshConfig.fromJson(String(bytes, Charsets.UTF_8)).also { parsed ->
                configCache = parsed
                configFileMtime = mtime
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SSH config", e)
            null
        }
    }

    fun hasConfig(): Boolean = configFile.exists()

    // -------------------------------------------------------------------------
    // Private keys
    // -------------------------------------------------------------------------

    @Synchronized
    fun savePrivateKey(hostAlias: String, keyBytes: ByteArray) {
        writeEncrypted(keyFile(hostAlias), keyBytes)
    }

    fun loadPrivateKey(hostAlias: String): ByteArray? {
        val f = keyFile(hostAlias)
        if (!f.exists()) return null
        return readEncrypted(f)
    }

    // -------------------------------------------------------------------------
    // House-keeping
    // -------------------------------------------------------------------------

    @Synchronized
    fun clearAll() {
        secureDir.deleteRecursively()
        secureDir.mkdirs()
        keysDir.mkdirs()
        configCache = null
        configFileMtime = 0L
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Write [data] to an [EncryptedFile] at [file].
     * [EncryptedFile] cannot overwrite – the target must be deleted first.
     */
    private fun writeEncrypted(file: File, data: ByteArray) {
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use { it.write(data) }
    }

    private fun readEncrypted(file: File): ByteArray? = try {
        encryptedFile(file).openFileInput().use { it.readBytes() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read encrypted file: ${file.name}", e)
        null
    }

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    private fun keyFile(hostAlias: String): File {
        val safeName = hostAlias.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return File(keysDir, "$safeName.key.enc")
    }

    companion object {
        private const val TAG = "KeyStorage"
    }
}
