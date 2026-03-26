package io.github.carstenleue.sshfsprovider

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayOutputStream

/**
 * Main launcher activity.
 *
 * Allows the user to import an SSH config bundle (.tgz) containing:
 *   config       – OpenSSH-format config file (required)
 *   known_hosts  – host key verification file (optional but recommended)
 *   <keyfile>    – one or more private key files referenced in `config`
 *
 * Bundle format (flat structure, no sub-directories for key files):
 *   bundle.tgz
 *   ├── config
 *   ├── known_hosts          (optional)
 *   └── id_ed25519           (key file, referenced as IdentityFile in config)
 *
 * Keys are stored encrypted on-device using [KeyStorage] and are never
 * accessible outside the app's private storage.
 */
class ConfigImportActivity : AppCompatActivity() {

    private lateinit var keyStorage: KeyStorage

    private lateinit var statusText: TextView
    private lateinit var importButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var progressBar: CircularProgressIndicator

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importConfig(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_import)

        keyStorage = KeyStorage(this)

        statusText = findViewById(R.id.statusText)
        importButton = findViewById(R.id.importButton)
        clearButton = findViewById(R.id.clearButton)
        progressBar = findViewById(R.id.progressBar)

        importButton.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        clearButton.setOnClickListener { clearConfig() }

        updateStatus()
    }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    private fun importConfig(uri: Uri) {
        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { doImport(uri) }

            withContext(Dispatchers.Main) {
                setLoading(false)
                result.fold(
                    onSuccess = { (hostCount, keyCount) ->
                        contentResolver.notifyChange(
                            DocumentsContract.buildRootsUri(SshDocumentsProvider.AUTHORITY),
                            null,
                        )
                        updateStatus()
                        Snackbar.make(
                            importButton,
                            getString(R.string.import_success, hostCount, keyCount),
                            Snackbar.LENGTH_LONG,
                        ).show()
                    },
                    onFailure = { e ->
                        Snackbar.make(
                            importButton,
                            getString(R.string.import_error, e.message),
                            Snackbar.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    /**
     * Extracts the tgz, validates the config, and stores everything encrypted.
     * Runs on [Dispatchers.IO].
     * @return (number of hosts, number of keys imported)
     */
    private fun doImport(uri: Uri): Pair<Int, Int> {
        val entries = extractTgz(uri)

        val configBytes = entries["config"]
            ?: throw IllegalArgumentException(getString(R.string.error_no_config_entry))
        val configContent = String(configBytes, Charsets.UTF_8)
        val parsed = SshConfigParser.parse(configContent)

        require(parsed.hosts.isNotEmpty()) { getString(R.string.error_no_hosts) }

        val knownHosts = entries["known_hosts"]?.toString(Charsets.UTF_8)
        keyStorage.saveConfig(parsed.copy(knownHostsContent = knownHosts))

        var keysImported = 0
        for (host in parsed.hosts) {
            val keyName = host.identityFile ?: continue
            val keyBytes = entries[keyName] ?: continue
            keyStorage.savePrivateKey(host.alias, keyBytes)
            keyBytes.fill(0)
            keysImported++
        }

        return parsed.hosts.size to keysImported
    }

    /**
     * Extracts top-level files from a .tgz archive.
     * Rejects path-traversal attempts and files larger than [MAX_ENTRY_BYTES].
     */
    private fun extractTgz(uri: Uri): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        contentResolver.openInputStream(uri)!!.use { raw ->
            GzipCompressorInputStream(raw).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            // Normalise: strip leading ./ or /
                            val name = entry.name.trimStart('.', '/')
                            // Security: accept only flat (no sub-path) filenames
                            if (name.isNotEmpty() && !name.contains('/') && !name.contains("..")) {
                                if (entry.size <= MAX_ENTRY_BYTES) {
                                    val buf = ByteArrayOutputStream(entry.size.toInt().coerceAtLeast(0))
                                    tar.copyTo(buf)
                                    result[name] = buf.toByteArray()
                                }
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Clear
    // -------------------------------------------------------------------------

    private fun clearConfig() {
        keyStorage.clearAll()
        contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(SshDocumentsProvider.AUTHORITY),
            null,
        )
        updateStatus()
        Snackbar.make(importButton, R.string.config_cleared, Snackbar.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateStatus() {
        val config = keyStorage.loadConfig()
        if (config == null || config.hosts.isEmpty()) {
            statusText.setText(R.string.no_config_message)
            clearButton.isEnabled = false
        } else {
            val lines = config.hosts.joinToString("\n") { host ->
                "• ${host.alias}  (${host.user}@${host.hostname}:${host.port})"
            }
            statusText.text = getString(R.string.status_configured, lines)
            clearButton.isEnabled = true
        }
    }

    private fun setLoading(loading: Boolean) {
        importButton.isEnabled = !loading
        clearButton.isEnabled = !loading && keyStorage.hasConfig()
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    companion object {
        /** Maximum size of any single file extracted from the bundle (1 MiB). */
        private const val MAX_ENTRY_BYTES = 1024L * 1024L
    }
}
