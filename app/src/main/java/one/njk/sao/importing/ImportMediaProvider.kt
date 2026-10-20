package one.njk.sao.importing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** An exact, temporary read grant to one immutable queued snapshot. No source URI delegation or path traversal. */
class ImportMediaProvider: ContentProvider() {
    override fun onCreate()=true
    private fun task(uri: Uri): ImportStore.Task {
        if(uri.pathSegments.size!=1) throw FileNotFoundException()
        return requireNotNull(context).imports().store.task(uri.lastPathSegment.orEmpty()) ?: throw FileNotFoundException()
    }
    override fun getType(uri: Uri)=if(uri.lastPathSegment=="changes") "vnd.android.cursor.item/vnd.sao.imports" else task(uri).mime
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
        if(mode!="r") throw SecurityException("Read only")
        val file=task(uri).staged?.let(::File) ?: throw FileNotFoundException()
        val root=File(requireNotNull(context).noBackupFilesDir,"media-imports").canonicalFile
        if(file.canonicalFile.parentFile!=root) throw SecurityException("Invalid snapshot")
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): MatrixCursor {
        val task=task(uri); val fields=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(fields).apply { addRow(fields.map { when(it) { OpenableColumns.DISPLAY_NAME->task.name; OpenableColumns.SIZE->task.staged?.let(::File)?.length() ?: task.size; else->null } }) }
    }
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw SecurityException("Read only")
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int=throw SecurityException("Read only")
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int=throw SecurityException("Read only")
}
