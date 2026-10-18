package one.njk.sao.extension.local

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled models: no GMS installation, model download or cloud inference required. */
class MediaAnalyzer : AutoCloseable {
    private val labeler=ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(.60f).build())
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspend fun analyze(context: Context, asset: LocalAsset): Analysis = withContext(Dispatchers.IO) {
        val bitmap=MediaBitmaps.read(context,Uri.parse(asset.uri),asset.mime,1024)
        try {
            // ML Kit Tasks cannot cancel inference. Finish the current bounded frame before
            // recycling it/closing the engine; cancellation stops before the next file/write.
            withContext(NonCancellable) {
                val input=InputImage.fromBitmap(bitmap,0)
                val labels=labeler.process(input).result().sortedByDescending { it.confidence }.take(12).map { it.text }
                val text=recognizer.process(input).result().text
                Analysis((labels+LocalFormats.emojis(labels)).distinct(),text)
            }
        } finally { bitmap.recycle() }
    }
    override fun close() { labeler.close(); recognizer.close() }
    private suspend fun <T> Task<T>.result(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener(java.util.concurrent.Executor { it.run() }) { if(continuation.isActive) continuation.resume(it) }
        addOnFailureListener(java.util.concurrent.Executor { it.run() }) { if(continuation.isActive) continuation.resumeWithException(it) }
    }
}
