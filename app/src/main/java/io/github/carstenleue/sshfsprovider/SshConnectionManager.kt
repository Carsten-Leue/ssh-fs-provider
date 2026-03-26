package io.github.carstenleue.sshfsprovider

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException

/**
 * Manages JSch [Session] objects (one per host alias) and opens new [ChannelSftp]
 * channels on demand.
 *
 * Design:
 * - One SSH [Session] is maintained per host; it is reconnected automatically when lost.
 * - Each call to [openSftpChannel] opens a **dedicated** SFTP channel on the existing
 *   session.  This lets multiple files be read/written concurrently (SFTP supports
 *   multiplexed channels over a single SSH connection).
 * - Callers own the returned channel and must call [ChannelSftp.disconnect] when done
 *   (the [SshReadProxyCallback] / [SshWriteProxyCallback] do this in `onRelease`).
 * - Directory-listing operations use [withSftpChannel], which opens and closes a
 *   short-lived channel automatically.
 *
 * All public methods are `@Synchronized` and blocking; call them from a background
 * thread ([kotlinx.coroutines.Dispatchers.IO] or the SAF binder thread).
 */
class SshConnectionManager(private val keyStorage: KeyStorage) {

    private val sessions = HashMap<String, Session>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Opens a new [ChannelSftp] on the session for [hostAlias].
     * The caller is responsible for closing it via [ChannelSftp.disconnect].
     */
    @Synchronized
    @Throws(IOException::class)
    fun openSftpChannel(hostAlias: String): ChannelSftp {
        val session = getOrCreateSession(hostAlias)
        return try {
            (session.openChannel("sftp") as ChannelSftp).also {
                it.connect(CHANNEL_TIMEOUT_MS)
            }
        } catch (e: JSchException) {
            throw IOException("Failed to open SFTP channel for '$hostAlias': ${e.message}", e)
        }
    }

    /**
     * Opens a temporary channel, runs [block], then closes the channel.
     * Use this for short-lived operations like directory listings or stat calls.
     */
    @Throws(IOException::class)
    fun <T> withSftpChannel(hostAlias: String, block: (ChannelSftp) -> T): T {
        val ch = openSftpChannel(hostAlias)
        return try {
            block(ch)
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    @Synchronized
    fun disconnectAll() {
        sessions.values.forEach { runCatching { it.disconnect() } }
        sessions.clear()
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    @Synchronized
    @Throws(IOException::class)
    private fun getOrCreateSession(hostAlias: String): Session {
        val existing = sessions[hostAlias]
        if (existing != null && existing.isConnected) return existing
        existing?.let { runCatching { it.disconnect() } }
        sessions.remove(hostAlias)
        return createSession(hostAlias).also { sessions[hostAlias] = it }
    }

    @Throws(IOException::class)
    private fun createSession(hostAlias: String): Session {
        val config = keyStorage.loadConfig()
            ?: throw IOException("No SSH configuration found – import a config bundle first")

        val host = config.hosts.find { it.alias == hostAlias }
            ?: throw IOException("Host '$hostAlias' not found in stored config")

        val keyBytes = keyStorage.loadPrivateKey(hostAlias)
            ?: throw IOException("No private key stored for '$hostAlias'")

        try {
            val jsch = JSch()

            if (config.knownHostsContent != null) {
                jsch.setKnownHosts(config.knownHostsContent.byteInputStream())
            } else {
                Log.w(TAG, "No known_hosts – StrictHostKeyChecking disabled for $hostAlias")
            }

            jsch.addIdentity(hostAlias, keyBytes, null, null)
            keyBytes.fill(0) // zero key bytes immediately after handing to JSch

            val session = jsch.getSession(host.user, host.hostname, host.port)
            session.timeout = CONNECT_TIMEOUT_MS
            session.setConfig("StrictHostKeyChecking",
                if (config.knownHostsContent != null) "yes" else "no")
            session.setConfig("PreferredAuthentications", "publickey")
            session.setConfig("kex",
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                "ecdh-sha2-nistp256,diffie-hellman-group14-sha256")
            session.setConfig("server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256")
            session.connect(CONNECT_TIMEOUT_MS)

            Log.i(TAG, "Connected to $hostAlias (${host.user}@${host.hostname}:${host.port})")
            return session
        } catch (e: JSchException) {
            throw IOException("SSH connection to '$hostAlias' failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "SshConnectionManager"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val CHANNEL_TIMEOUT_MS = 15_000
    }
}
