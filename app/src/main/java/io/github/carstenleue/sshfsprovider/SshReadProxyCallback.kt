package io.github.carstenleue.sshfsprovider

import android.os.CancellationSignal
import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import java.io.IOException
import java.io.InputStream

/**
 * [ProxyFileDescriptorCallback] for reading remote SFTP files with full seek support.
 *
 * Thread-safety:
 *   All callbacks are serialised on [callbackThread] by
 *   [android.os.storage.StorageManager.openProxyFileDescriptor], so [sftp], [stream],
 *   and [streamOffset] need no additional locking.
 *
 * Channel lifecycle:
 *   The SFTP channel is opened **lazily** on the first [onRead] call rather than
 *   at construction time.  This avoids the race where the channel is established
 *   before the callback is registered and could be disconnected before first use.
 *
 * Seek strategy:
 *   - Forward seeks within [MAX_SKIP_BYTES] skip bytes in the existing [InputStream]
 *     (zero extra network round-trips).
 *   - Larger forward seeks and all backward seeks reopen the remote stream at the
 *     requested offset via [ChannelSftp.get] with a skip parameter – the server
 *     seeks directly and only the requested bytes travel over the wire.
 */
class SshReadProxyCallback(
    private val connectionManager: SshConnectionManager,
    private val hostAlias: String,
    private val remotePath: String,
    private val fileSize: Long,
    private val signal: CancellationSignal?,
    private val callbackThread: HandlerThread,
) : ProxyFileDescriptorCallback() {

    private var sftp: ChannelSftp? = null
    private var stream: InputStream? = null
    private var streamOffset: Long = -1L

    override fun onGetSize(): Long = fileSize

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        signal?.throwIfCanceled()
        ensureChannel()
        ensureStream(offset)

        var read = 0
        try {
            while (read < size) {
                val n = stream!!.read(data, read, size - read)
                if (n == -1) break
                read += n
                streamOffset += n
            }
        } catch (e: IOException) {
            Log.e(TAG, "Read error at offset $offset in $remotePath", e)
            throw ErrnoException("onRead", OsConstants.EIO)
        }
        return read
    }

    override fun onRelease() {
        closeStream()
        runCatching { sftp?.disconnect() }
        callbackThread.quitSafely()
    }

    // -------------------------------------------------------------------------

    private fun ensureChannel() {
        if (sftp != null && sftp!!.isConnected) return
        try {
            sftp = connectionManager.openSftpChannel(hostAlias)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot open SFTP channel for $hostAlias", e)
            throw ErrnoException("ensureChannel", OsConstants.EIO)
        }
    }

    /**
     * Ensures [stream] is positioned at [offset].
     *
     * - Equal to current position: no-op.
     * - Small forward seek (≤ [MAX_SKIP_BYTES]): skip within the existing stream.
     * - All other seeks: close and reopen the stream at [offset].
     */
    private fun ensureStream(offset: Long) {
        val s = stream

        if (s != null && streamOffset == offset) return

        // Small forward seek: skip in-stream to avoid a network round-trip.
        if (s != null && offset > streamOffset) {
            val toSkip = offset - streamOffset
            if (toSkip <= MAX_SKIP_BYTES) {
                try {
                    var remaining = toSkip
                    while (remaining > 0) {
                        val n = s.skip(remaining)
                        if (n <= 0) break
                        remaining -= n
                    }
                    if (remaining == 0L) {
                        streamOffset = offset
                        return
                    }
                } catch (_: IOException) {
                    // Fall through to reopen.
                }
            }
        }

        // Reopen at the requested offset (handles backward seeks and large jumps).
        closeStream()
        try {
            @Suppress("DEPRECATION")
            stream = sftp!!.get(remotePath, null as SftpProgressMonitor?, offset)
            streamOffset = offset
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to open stream at offset $offset for $remotePath", e)
            throw ErrnoException("ensureStream", OsConstants.EIO)
        }
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
        streamOffset = -1L
    }

    companion object {
        private const val TAG = "SshReadProxyCallback"

        /** Skip within the current stream rather than reopening for seeks this small. */
        private const val MAX_SKIP_BYTES = 256 * 1024L // 256 KB
    }
}
