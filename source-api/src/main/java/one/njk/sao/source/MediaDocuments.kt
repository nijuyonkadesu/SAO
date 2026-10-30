package one.njk.sao.source

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*

/** Metadata only. SAF has no universal date/generation cursor, so new document IDs must be enumerated. */
object MediaDocuments {
    data class Entry(val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long,
        val document: String? = null) {
        val directory get() = mime==DocumentsContract.Document.MIME_TYPE_DIR
        val media get() = mime.startsWith("image/") || mime.startsWith("video/")
        // URI is already the database key. Compare metadata directly, without hashing unchanged entries.
        val fingerprint get() = "$size|$modified|$mime"
    }
    fun mime(name: String, reported: String?) = reported?.takeUnless { it=="application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase()) ?: "application/octet-stream"
    suspend fun details(resolver: ContentResolver, uri: Uri): Entry = query { signal ->
        require(uri.scheme=="content")
        resolver.query(uri,null,null,null,null,signal)?.use { c ->
            if(!c.moveToFirst()) throw ExtensionFailure("MEDIA_MISSING")
            fun text(key: String) = c.getColumnIndex(key).takeIf { it>=0 && !c.isNull(it) }?.let(c::getString)
            val name=text(OpenableColumns.DISPLAY_NAME).orEmpty().ifBlank { "media" }
            val modified=text(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull()
                ?: text(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)?.toLongOrNull()?.times(1000) ?: 0L
            Entry(uri,name,mime(name,resolver.getType(uri)),text(OpenableColumns.SIZE)?.toLongOrNull() ?: -1,modified)
        } ?: throw ExtensionFailure("MEDIA_MISSING")
    }
    suspend fun children(resolver: ContentResolver, tree: Uri, document: String, start: Int=0, consume: (Entry) -> Unit) = query { signal ->
        val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,document)
        resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null,signal)?.use { c ->
            if(c.extras.getBoolean(DocumentsContract.EXTRA_LOADING,false)) throw ExtensionFailure("FOLDER_LOADING")
            if(start>0) c.moveToPosition(start-1)
            while(c.moveToNext()) {
                signal.throwIfCanceled()
                val id=c.getString(0); val name=c.getString(1).orEmpty()
                consume(Entry(DocumentsContract.buildDocumentUriUsingTree(tree,id),name,mime(name,c.getString(2)),
                    if(c.isNull(3)) -1 else c.getLong(3),if(c.isNull(4)) 0 else c.getLong(4),id))
            }
        } ?: throw ExtensionFailure("FOLDER_UNAVAILABLE")
    }
    private suspend fun <T> query(block: (CancellationSignal) -> T): T = withContext(Dispatchers.IO) { coroutineScope {
        val signal=CancellationSignal()
        val closer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { signal.cancel() } }
        try { block(signal) } finally { closer.cancel() }
    } }
}
