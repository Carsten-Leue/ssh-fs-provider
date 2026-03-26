package io.github.carstenleue.sshfsprovider

import android.os.CancellationSignal
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
 * Random-access reads (e.g. from video players, PDF viewers, or media indexers) work
 * efficiently because each seek simply reopens the SFTP stream at the requested offset
 * using `ChannelSftp.get(path, monitor, skip)` — the server sends only the bytes
 * actually needed, not the whole file.
 *
 * Sequential reads reuse the open [InputStream] without any reconnection overhead.
 *
 * Thread-safety: callbacks from [android.os.storage.StorageManager.openProxyFileDescriptor]
 * are serialised on the supplied [android.os.Handler], so no additional locking is needed here.
 */
class SshReadProxyCallback(
    private val sftp: ChannelSftp,
    private val remotePath: String,
    private val fileSize: Long,
    private val signal: CancellationSignal?,
) : ProxyFileDescriptorCallback() {

    private var stream: InputStream? = null
    private var streamOffset: Long = -1L

    override fun onGetSize(): Long = fileSize

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        signal?.throwIfCanceled()
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
        runCatching { sftp.disconnect() }
    }

    // -------------------------------------------------------------------------

    /**
     * Opens (or re-opens) the remote stream at [offset].
     * Re-opening is cheap for SFTP because the server jumps directly to the
     * requested position without transferring skipped bytes.
     */
    private fun ensureStream(offset: Long) {
        if (stream != null && streamOffset == offset) return
        closeStream()
        try {
            @Suppress("DEPRECATION")
            stream = sftp.get(remotePath, null as SftpProgressMonitor?, offset)
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
    }
}
