package io.github.carstenleue.sshfsprovider

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * [ProxyFileDescriptorCallback] for writing to a remote SFTP file.
 *
 * Strategy: buffer all writes into a local temp file (supports random access / seek),
 * then upload the complete file to the SFTP server in [onRelease]. This handles apps
 * that seek while writing (e.g. text editors that rewrite the header after the body).
 *
 * The temp file is stored in [android.content.Context.getCacheDir] and deleted after
 * the upload completes or fails.
 *
 * Thread-safety: callbacks from [android.os.storage.StorageManager.openProxyFileDescriptor]
 * are serialised on the supplied [android.os.Handler].
 */
class SshWriteProxyCallback(
    private val sftp: ChannelSftp,
    private val remotePath: String,
    private val cacheFile: File,
) : ProxyFileDescriptorCallback() {

    private val raf = RandomAccessFile(cacheFile, "rw")

    override fun onGetSize(): Long = raf.length()

    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
        try {
            raf.seek(offset)
            raf.write(data, 0, size)
            return size
        } catch (e: IOException) {
            Log.e(TAG, "Write error at offset $offset in $remotePath", e)
            throw ErrnoException("onWrite", OsConstants.EIO)
        }
    }

    /**
     * Apps that open a file in read-write mode may read it back after writing.
     */
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        return try {
            raf.seek(offset)
            val n = raf.read(data, 0, size)
            if (n == -1) 0 else n
        } catch (e: IOException) {
            Log.e(TAG, "Read-back error at offset $offset in $remotePath", e)
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    /**
     * Called when the file descriptor is closed. Upload the buffered content to
     * the SFTP server, then clean up.
     */
    override fun onRelease() {
        try {
            raf.close()
            cacheFile.inputStream().use { input ->
                sftp.put(input, remotePath, ChannelSftp.OVERWRITE)
            }
            Log.d(TAG, "Upload complete: $remotePath (${cacheFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for $remotePath", e)
        } finally {
            cacheFile.delete()
            runCatching { sftp.disconnect() }
        }
    }

    companion object {
        private const val TAG = "SshWriteProxyCallback"
    }
}
