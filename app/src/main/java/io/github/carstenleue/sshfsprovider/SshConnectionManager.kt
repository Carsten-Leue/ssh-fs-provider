package io.github.carstenleue.sshfsprovider

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException

/**
 * Manages a pool of JSch [Session] + [ChannelSftp] pairs, one per host alias.
 *
 * All public methods are synchronised and blocking; call them from a background
 * thread (the SAF binder thread or [kotlinx.coroutines.Dispatchers.IO]).
 *
 * Key security decisions:
 * - Private key bytes are zeroed immediately after [JSch.addIdentity].
 * - [StrictHostKeyChecking] is set to "yes" when a known_hosts file was
 *   imported, otherwise "no" (with a log warning).
 * - Only public-key authentication is offered.
 */
class SshConnectionManager(private val keyStorage: KeyStorage) {

    private data class PoolEntry(val session: Session, val channel: ChannelSftp)

    private val pool = HashMap<String, PoolEntry>()

    /**
     * Returns a connected [ChannelSftp] for [hostAlias], reusing an existing
     * session when possible or establishing a new one.
     */
    @Synchronized
    @Throws(IOException::class)
    fun getSftpChannel(hostAlias: String): ChannelSftp {
        val existing = pool[hostAlias]
        if (existing != null && existing.session.isConnected && existing.channel.isConnected) {
            return existing.channel
        }
        existing?.let { closeEntry(it) }
        pool.remove(hostAlias)

        val entry = createEntry(hostAlias)
        pool[hostAlias] = entry
        return entry.channel
    }

    @Synchronized
    fun disconnectAll() {
        pool.values.forEach { closeEntry(it) }
        pool.clear()
    }

    // -------------------------------------------------------------------------

    @Throws(IOException::class)
    private fun createEntry(hostAlias: String): PoolEntry {
        val config = keyStorage.loadConfig()
            ?: throw IOException("No SSH configuration found – import a config bundle first")

        val host = config.hosts.find { it.alias == hostAlias }
            ?: throw IOException("Host '$hostAlias' not found in stored config")

        val keyBytes = keyStorage.loadPrivateKey(hostAlias)
            ?: throw IOException("No private key stored for '$hostAlias'")

        try {
            val jsch = JSch()

            // Apply known_hosts if provided; otherwise warn and skip host-key verification.
            if (config.knownHostsContent != null) {
                jsch.setKnownHosts(config.knownHostsContent.byteInputStream())
            } else {
                Log.w(TAG, "No known_hosts file – StrictHostKeyChecking disabled for $hostAlias")
            }

            // Add identity and immediately zero the in-memory key bytes.
            jsch.addIdentity(hostAlias, keyBytes, null, null)
            keyBytes.fill(0)

            val session: Session = jsch.getSession(host.user, host.hostname, host.port)
            session.timeout = CONNECT_TIMEOUT_MS
            session.setConfig("StrictHostKeyChecking",
                if (config.knownHostsContent != null) "yes" else "no")
            session.setConfig("PreferredAuthentications", "publickey")
            // Prefer modern key-exchange and host-key algorithms
            session.setConfig("kex",
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                "ecdh-sha2-nistp256,diffie-hellman-group14-sha256")
            session.setConfig("server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256")
            session.connect(CONNECT_TIMEOUT_MS)

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(CHANNEL_TIMEOUT_MS)

            Log.i(TAG, "Connected to $hostAlias (${host.user}@${host.hostname}:${host.port})")
            return PoolEntry(session, channel)
        } catch (e: JSchException) {
            throw IOException("SSH connection to '$hostAlias' failed: ${e.message}", e)
        }
    }

    private fun closeEntry(entry: PoolEntry) {
        runCatching { entry.channel.disconnect() }
        runCatching { entry.session.disconnect() }
    }

    companion object {
        private const val TAG = "SshConnectionManager"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val CHANNEL_TIMEOUT_MS = 15_000
    }
}
