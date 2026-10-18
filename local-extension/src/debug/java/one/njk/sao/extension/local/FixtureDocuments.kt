package one.njk.sao.extension.local

import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

/** Synthetic private documents, deliberately absent from release APKs and source discovery. */
class FixtureDocuments : DocumentsProvider() {
    private val root get()=File(requireNotNull(context).filesDir,"test-documents").apply { mkdirs() }
    override fun onCreate()=true
    override fun isChildDocument(parentDocumentId: String,documentId: String) = file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath+"/")
    private fun file(id: String): File = if(id=="root") root else File(root,id.removePrefix("root/")).also {
        require(it.canonicalPath.startsWith(root.canonicalPath+"/"))
    }
    override fun queryRoots(projection: Array<out String>?)=MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE)).apply { addRow(arrayOf("fixture","root","Synthetic files")) }
    override fun queryDocument(documentId: String,projection: Array<out String>?)=cursor(listOf(file(documentId)),projection)
    override fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?)=cursor(file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name },projection)
    private fun cursor(files: List<File>,projection: Array<out String>?): MatrixCursor {
        val columns=projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE)
        return MatrixCursor(columns).apply { files.forEach { f -> addRow(columns.map { name -> when(name) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> if(f==root) "root" else "root/"+f.relativeTo(root).path
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> f.name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if(f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else LocalFormats.mime(f.name,null) ?: "application/octet-stream"
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> f.lastModified()
            DocumentsContract.Document.COLUMN_SIZE -> f.length()
            DocumentsContract.Document.COLUMN_FLAGS -> 0
            else -> null
        } }.toTypedArray<Any?>()) } }
    }
    override fun openDocument(documentId: String,mode: String,signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode=="r"); return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
