package io.github.carstenleue.sshfsprovider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import java.io.File
import java.io.FileNotFoundException

/**
 * Storage Access Framework [DocumentsProvider] that exposes SFTP file systems
 * as document roots, making them accessible from the Android file picker and
 * any SAF-aware app (Files, photo pickers, text editors, …).
 *
 * Document ID format: "<hostAlias>:<absolutePath>"
 *   Root:    "myserver:/"
 *   File:    "myserver:/home/alice/docs/report.pdf"
 *
 * Large-file support:
 *   [openDocument] uses [StorageManager.openProxyFileDescriptor] with
 *   [SshReadProxyCallback] / [SshWriteProxyCallback].  Reads support
 *   arbitrary seeking so video players, PDF viewers, and other apps that
 *   access random byte ranges work correctly without downloading the whole
 *   file first.
 *
 * Provider methods are invoked on binder threads (already off the main thread),
 * so blocking SFTP I/O is safe here.
 */
class SshDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "io.github.carstenleue.sshfsprovider.documents"
        private const val TAG = "SshDocumentsProvider"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_SUMMARY,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }

    private lateinit var keyStorage: KeyStorage
    private lateinit var connectionManager: SshConnectionManager
    private lateinit var storageManager: StorageManager

    /**
     * Dedicated thread for [ProxyFileDescriptorCallback] invocations.
     * A single thread is sufficient – concurrent open files each have their own
     * channel and their callbacks are dispatched sequentially on this looper.
     */
    private val callbackThread = HandlerThread("SshFsCallbacks").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)

    override fun onCreate(): Boolean {
        keyStorage = KeyStorage(context!!)
        connectionManager = SshConnectionManager(keyStorage)
        storageManager = context!!.getSystemService(StorageManager::class.java)
        return true
    }

    // -------------------------------------------------------------------------
    // Root enumeration
    // -------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildRootsUri(AUTHORITY),
        )

        val config = keyStorage.loadConfig() ?: return cursor

        for (host in config.hosts) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, host.alias)
                add(Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_TITLE, host.alias)
                add(Root.COLUMN_DOCUMENT_ID, "${host.alias}:/")
                add(Root.COLUMN_ICON, R.drawable.ic_ssh_root)
                add(Root.COLUMN_SUMMARY, "${host.user}@${host.hostname}:${host.port}")
            }
        }
        return cursor
    }

    // -------------------------------------------------------------------------
    // Document metadata
    // -------------------------------------------------------------------------

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (hostAlias, path) = splitDocId(documentId)

        if (path == "/") {
            // Synthetic root directory – no SFTP connection needed.
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, hostAlias)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            try {
                connectionManager.withSftpChannel(hostAlias) { sftp ->
                    val attrs = sftp.stat(path)
                    val name = path.substringAfterLast('/')
                    cursor.addDocumentRow(documentId, name, attrs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "queryDocument failed for $documentId", e)
                throw FileNotFoundException("Cannot stat $documentId: ${e.message}")
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
        )

        val (hostAlias, parentPath) = splitDocId(parentDocumentId)

        try {
            connectionManager.withSftpChannel(hostAlias) { sftp ->
                val entries = sftp.ls(parentPath)
                for (item in entries) {
                    val entry = item as? ChannelSftp.LsEntry ?: continue
                    val name = entry.filename
                    if (name == "." || name == "..") continue
                    val childPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
                    cursor.addDocumentRow("$hostAlias:$childPath", name, entry.attrs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryChildDocuments failed for $parentDocumentId", e)
            // Return the partial/empty cursor so the picker shows something.
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val (parentAlias, parentPath) = splitDocId(parentDocumentId)
            val (childAlias, childPath) = splitDocId(documentId)
            if (parentAlias != childAlias) return false
            val prefix = if (parentPath.endsWith('/')) parentPath else "$parentPath/"
            childPath.startsWith(prefix)
        } catch (_: FileNotFoundException) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // File I/O  –  uses ProxyFileDescriptor for seek / large-file support
    // -------------------------------------------------------------------------

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val (hostAlias, path) = splitDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        val isWrite = (accessMode and
            (ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_READ_WRITE)) != 0

        return if (isWrite) {
            openForWrite(hostAlias, path)
        } else {
            openForRead(hostAlias, path, signal)
        }
    }

    private fun openForRead(
        hostAlias: String,
        path: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        // Open a dedicated SFTP channel; the callback closes it in onRelease().
        val sftp = connectionManager.openSftpChannel(hostAlias)
        val fileSize = try {
            sftp.stat(path).size
        } catch (e: Exception) {
            runCatching { sftp.disconnect() }
            throw FileNotFoundException("Cannot stat $hostAlias:$path: ${e.message}")
        }

        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            SshReadProxyCallback(sftp, path, fileSize, signal),
            callbackHandler,
        )
    }

    private fun openForWrite(
        hostAlias: String,
        path: String,
    ): ParcelFileDescriptor {
        val sftp = connectionManager.openSftpChannel(hostAlias)
        val cacheFile = File.createTempFile("sftp_write_", ".tmp", context!!.cacheDir)

        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_WRITE,
            SshWriteProxyCallback(sftp, path, cacheFile),
            callbackHandler,
        )
    }

    // -------------------------------------------------------------------------
    // Mutations
    // -------------------------------------------------------------------------

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val (hostAlias, parentPath) = splitDocId(parentDocumentId)
        val childPath = if (parentPath == "/") "/$displayName" else "$parentPath/$displayName"

        try {
            connectionManager.withSftpChannel(hostAlias) { sftp ->
                if (mimeType == Document.MIME_TYPE_DIR) {
                    sftp.mkdir(childPath)
                } else {
                    sftp.put(ByteArray(0).inputStream(), childPath)
                }
            }
        } catch (e: Exception) {
            throw FileNotFoundException("Cannot create $childPath: ${e.message}")
        }
        return "$hostAlias:$childPath"
    }

    override fun deleteDocument(documentId: String) {
        val (hostAlias, path) = splitDocId(documentId)
        try {
            connectionManager.withSftpChannel(hostAlias) { sftp ->
                if (sftp.stat(path).isDir) sftp.rmdir(path) else sftp.rm(path)
            }
        } catch (e: Exception) {
            throw FileNotFoundException("Cannot delete $documentId: ${e.message}")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (hostAlias, path) = splitDocId(documentId)
        val newPath = "${path.substringBeforeLast('/')}/$displayName"
        try {
            connectionManager.withSftpChannel(hostAlias) { sftp ->
                sftp.rename(path, newPath)
            }
        } catch (e: Exception) {
            throw FileNotFoundException("Cannot rename $documentId: ${e.message}")
        }
        return "$hostAlias:$newPath"
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun MatrixCursor.addDocumentRow(docId: String, name: String, attrs: SftpATTRS) {
        val isDir = attrs.isDir
        val mimeType = if (isDir) Document.MIME_TYPE_DIR else getMimeType(name)
        val flags = if (isDir) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
        } else {
            Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
        }
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else attrs.size)
            add(Document.COLUMN_LAST_MODIFIED, attrs.mTime.toLong() * 1000L)
        }
    }

    private fun getMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    @Throws(FileNotFoundException::class)
    private fun splitDocId(documentId: String): Pair<String, String> {
        val idx = documentId.indexOf(':')
        if (idx < 0) throw FileNotFoundException("Invalid document ID: $documentId")
        val alias = documentId.substring(0, idx)
        val path = documentId.substring(idx + 1).ifEmpty { "/" }
        return alias to path
    }
}
