package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** A newer request invalidates older work before its next platform publication. */
class ClipboardRequest {
    private val version = AtomicLong()
    fun next() = version.incrementAndGet()
    fun current(expected: Long) = version.get() == expected
}

@Singleton
class ClipboardPublisher @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers,
    private val exports: MediaExportStore = MediaExportStore(context,dispatchers)) {
    private val publications = Mutex()
    suspend fun publish(item: MediaItem, media: ExportedMedia, request: ClipboardRequest, version: Long): Boolean =
        withContext(dispatchers.clipboard) {
            publications.withLock {
                if (!request.current(version)) return@withLock false
                // Explicit MIME avoids a provider query while composing ClipData on the UI thread.
                val clip = ClipData(ClipDescription(item.title, arrayOf(media.mimeType)), ClipData.Item(media.uri))
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                withContext(NonCancellable) { exports.clipboard(media.file) }
                request.current(version)
            }
        }
    suspend fun publishFilename(item: MediaItem, request: ClipboardRequest, version: Long): Boolean =
        withContext(dispatchers.clipboard) {
            publications.withLock {
                val name=item.filename?.takeIf { it.isNotBlank() } ?: return@withLock false
                if(!request.current(version)) return@withLock false
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(context.getString(one.njk.sao.R.string.media_filename),name))
                withContext(NonCancellable) { exports.clipboard(null) }
                request.current(version)
            }
        }

}
