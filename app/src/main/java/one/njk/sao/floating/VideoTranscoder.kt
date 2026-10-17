package one.njk.sao.floating

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One explicit export, no idle worker, polling, network or wake lock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoTranscoder @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers) {
    suspend fun mp4(input: File, output: File) = withContext(dispatchers.io) {
        val thread = HandlerThread("SAO video export").apply { start() }
        val handler = Handler(thread.looper)
        val worker = handler.asCoroutineDispatcher("SAO video export")
        var transformer: Transformer? = null
        try {
            withContext(worker) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }
                    val created = Transformer.Builder(context).setLooper(thread.looper)
                        .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(listener).build()
                    transformer = created
                    continuation.invokeOnCancellation { handler.post { created.cancel() } }
                    created.start(EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(input))).build(), output.path)
                }
            }
        } finally {
            withContext(NonCancellable + worker) { transformer?.cancel() }
            thread.quitSafely()
        }
    }
}

/** A capability rejection happens before dispatch. An uncertain send result must never be retried. */
internal object VideoCompatibility {
    fun mime(original: String, accepts: (String) -> Boolean): String =
        if (original == "video/webm" && !accepts(original) && accepts("video/mp4")) "video/mp4" else original
}
