package io.github.carstenleue.sshfsprovider

import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * [ProxyFileDescriptorCallback] for writing to a remote SFTP file.
 *
 * Strategy:
 *   All writes are buffered in a local temp file ([cacheFile]) using a
 *   [RandomAccessFile], which supports random-access / seek-while-writing used
 *   by editors that rewrite headers after the body.  On [onRelease], the
 *   complete temp file is uploaded atomically:
 *
 *     1. Upload to a temporary remote path `<target>.part`.
 *     2. Rename the temp path to `<target>` (atomic replace on OpenSSH via
 *        `posix-rename@openssh.com`; or delete-then-rename on older servers).
 *     3. Delete the local temp file.
 *
 *   If the upload or rename fails the remote target is **not** truncated or
 *   corrupted – the existing file is preserved until step 2 succeeds.
 *
 * Thread-safety:
 *   All callbacks are serialised on [callbackThread] by
 *   [android.os.storage.StorageManager.openProxyFileDescriptor], so [raf] needs
 *   no additional locking.
 */
class SshWriteProxyCallback(
    private val sftp: ChannelSftp,
    private val remotePath: String,
    private val cacheFile: File,
    private val callbackThread: HandlerThread,
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

    /** Apps that open read-write mode may read data back after writing. */
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
     * Upload the buffered content to the SFTP server atomically, then clean up.
     *
     * The remote file is only replaced once [sftp.rename] succeeds, so a network
     * failure during upload leaves the original file intact.
     */
    override fun onRelease() {
        val tempRemotePath = "$remotePath.part"
        try {
            // Close RAF first; ensure it's always closed even if upload throws.
            try {
                raf.close()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close temp file buffer", e)
            }

            // Upload to a staging path.
            cacheFile.inputStream().use { input ->
                sftp.put(input, tempRemotePath, ChannelSftp.OVERWRITE)
            }

            // Atomic rename.  SFTP v3 rename fails if the destination exists,
            // so fall back to delete-then-rename on non-OpenSSH servers.
            try {
                sftp.rename(tempRemotePath, remotePath)
            } catch (e: SftpException) {
                runCatching { sftp.rm(remotePath) }
                sftp.rename(tempRemotePath, remotePath)
            }

            Log.d(TAG, "Upload complete: $remotePath (${cacheFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for $remotePath", e)
            runCatching { sftp.rm(tempRemotePath) } // clean up the staging file
        } finally {
            cacheFile.delete()
            runCatching { sftp.disconnect() }
            callbackThread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "SshWriteProxyCallback"
    }
}
