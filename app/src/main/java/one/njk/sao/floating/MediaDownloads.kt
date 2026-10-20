package one.njk.sao.floating

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import one.njk.sao.source.FloatingDispatchers
import one.njk.sao.source.MediaItem
import javax.inject.Inject

/** Save original bytes independently of the home Share rendition, with a pending row until complete. */
class MediaDownloads @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers) {
    suspend fun save(item: MediaItem, media: ExportedMedia) = withContext(dispatchers.io) {
        val collection = if(item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,item.filename ?: media.file.name)
            put(MediaStore.MediaColumns.MIME_TYPE,media.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH,"${if(item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/SAO")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(collection,values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output -> media.file.inputStream().use { it.copyTo(output) } }
            resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
        } catch(error: Exception) { resolver.delete(uri,null,null); throw error }
    }
}
