package io.github.carstenleue.sshfsprovider

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream

/**
 * Main launcher activity.
 *
 * Allows the user to:
 *   - Import an SSH config bundle (.tgz) containing an OpenSSH config file,
 *     optional known_hosts, and private key files.
 *   - Export the currently stored config and keys back to a .tgz bundle so
 *     they can be backed up or transferred to another device.
 *   - Clear all stored credentials from the device.
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

    /**
     * Launches the system file-save picker so the user can choose where to
     * write the exported bundle.  The result URI is forwarded to [exportConfig].
     */
    private val createExportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
            uri?.let { exportConfig(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_import)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

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
    // Toolbar menu
    // -------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Export is only meaningful when there is a config to export.
        menu.findItem(R.id.action_export)?.isEnabled = keyStorage.hasConfig()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export -> {
            if (keyStorage.hasConfig()) {
                createExportFile.launch(getString(R.string.export_filename))
            } else {
                Snackbar.make(importButton, R.string.export_no_config, Snackbar.LENGTH_SHORT).show()
            }
            true
        }
        R.id.action_help -> {
            startActivity(Intent(this, HelpActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
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
                        invalidateOptionsMenu()
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
    // Export
    // -------------------------------------------------------------------------

    private fun exportConfig(uri: Uri) {
        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { doExport(uri) }

            withContext(Dispatchers.Main) {
                setLoading(false)
                result.fold(
                    onSuccess = { (hostCount, keyCount) ->
                        Snackbar.make(
                            importButton,
                            getString(R.string.export_success, hostCount, keyCount),
                            Snackbar.LENGTH_LONG,
                        ).show()
                    },
                    onFailure = { e ->
                        Snackbar.make(
                            importButton,
                            getString(R.string.export_error, e.message),
                            Snackbar.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    /**
     * Re-assembles the config, known_hosts, and all stored private keys into a
     * .tgz bundle and writes it to [uri].
     *
     * The archive layout mirrors what the import logic expects:
     *   bundle.tgz
     *   ├── config         – OpenSSH-format config, reconstructed from stored data
     *   ├── known_hosts    – host key file (only if present in stored config)
     *   └── <keyfile>      – one entry per host key, named after IdentityFile
     *
     * Runs on [Dispatchers.IO].
     * @return (number of hosts, number of keys exported)
     */
    private fun doExport(uri: Uri): Pair<Int, Int> {
        val config = keyStorage.loadConfig()
            ?: throw IllegalStateException(getString(R.string.export_no_config))

        val keys = keyStorage.loadAllPrivateKeys()

        contentResolver.openOutputStream(uri)!!.use { out ->
            writeTgz(out, config, keys)
        }

        return config.hosts.size to keys.size
    }

    /**
     * Writes the config bundle as a .tgz to [out].
     * Key bytes are zeroed after being written into the archive stream.
     */
    private fun writeTgz(out: java.io.OutputStream, config: SshConfig, keys: Map<String, ByteArray>) {
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                // config file – reconstruct from stored SshConfig
                val configBytes = buildConfigText(config).toByteArray(Charsets.UTF_8)
                tar.putArchiveEntry(TarArchiveEntry("config").also { it.size = configBytes.size.toLong() })
                tar.write(configBytes)
                tar.closeArchiveEntry()

                // known_hosts (optional)
                val knownHostsContent = config.knownHostsContent
                if (knownHostsContent != null) {
                    val khBytes = knownHostsContent.toByteArray(Charsets.UTF_8)
                    tar.putArchiveEntry(TarArchiveEntry("known_hosts").also { it.size = khBytes.size.toLong() })
                    tar.write(khBytes)
                    tar.closeArchiveEntry()
                }

                // one key file per host alias
                for (host in config.hosts) {
                    val keyName = host.identityFile ?: continue
                    val keyBytes = keys[host.alias] ?: continue
                    tar.putArchiveEntry(TarArchiveEntry(keyName).also { it.size = keyBytes.size.toLong() })
                    tar.write(keyBytes)
                    tar.closeArchiveEntry()
                    keyBytes.fill(0)
                }
            }
        }
    }

    /**
     * Reconstructs an OpenSSH-format config file text from a [SshConfig].
     * Each host gets its own `Host` block with the stored fields.
     */
    private fun buildConfigText(config: SshConfig): String = buildString {
        for (host in config.hosts) {
            append("Host ${host.alias}\n")
            append("    HostName ${host.hostname}\n")
            append("    User ${host.user}\n")
            append("    Port ${host.port}\n")
            if (host.identityFile != null) {
                append("    IdentityFile ${host.identityFile}\n")
            }
            append("\n")
        }
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
        invalidateOptionsMenu()
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
