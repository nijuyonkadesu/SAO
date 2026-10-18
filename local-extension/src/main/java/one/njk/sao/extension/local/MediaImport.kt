package one.njk.sao.extension.local

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.provider.MediaStore
import java.io.IOException

/** Persisted picker references are indexed in place; cloud bytes remain under the provider's control. */
internal object MediaImport {
    data class Details(val name: String,val mime: String,val modified: Long,val size: Long)
    fun details(context: Context,member: LocalStore.SelectedMedia,signal: CancellationSignal): Details {
        val uri=Uri.parse(member.uri)
        require(uri.scheme=="content")
        val resolver=context.contentResolver
        // Provider-specific date columns are optional. All providers support the openable name/size contract.
        val cursor=resolver.query(uri,null,null,null,null,signal) ?: throw IOException("Media unavailable")
        return cursor.use { c ->
            if(!c.moveToFirst()) throw IOException("Media unavailable")
            fun text(column: String)=c.getColumnIndex(column).takeIf { it>=0 }?.let { if(c.isNull(it)) null else c.getString(it) }
            val reported=resolver.getType(uri)
            val name=text(OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
            val mime=LocalFormats.mime(name,reported) ?: throw UnsupportedOperationException("Unsupported media")
            val date=text(MediaStore.MediaColumns.DATE_MODIFIED)?.toLongOrNull()?.takeIf { it>0 }?.times(1000) ?: member.added
            Details(name,mime,date,text(OpenableColumns.SIZE)?.toLongOrNull() ?: 0)
        }
    }
}
