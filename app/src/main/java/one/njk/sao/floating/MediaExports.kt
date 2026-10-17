package one.njk.sao.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ExportedMedia(val uri: Uri, val mimeType: String, val file: File)

@Singleton
class MediaExports @Inject constructor(@ApplicationContext private val context: Context,
    private val source: MediaSource,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers(),
    private val transcoder: VideoTranscoder = VideoTranscoder(context, dispatchers)) {
    private val mutex = Mutex()

    suspend fun prepare(item: MediaItem, rendition: Rendition): ExportedMedia = withContext(dispatchers.io) {
        require(!item.hasMotion || rendition == Rendition.MEDIA) { "Animated sticker conversion is unavailable" }
        require(item.id.matches(Regex("[a-z0-9_-]+")))
        val (extension, mime) = when {
            item.isVideo -> (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType) ?: "mp4") to item.mimeType
            item.animated -> (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType) ?: "gif") to item.mimeType
            rendition == Rendition.MEDIA -> "jpg" to "image/jpeg"
            rendition == Rendition.STICKER_WEBP -> "webp" to "image/webp"
            else -> "png" to "image/png"
        }
        val dir = File(context.filesDir, "floating_exports/v1").apply { mkdirs() }
        val file = File(dir, "${item.id}-${rendition.name.lowercase()}.$extension")
        mutex.withLock {
            if (!file.exists()) {
                val staging = File(dir, file.name + ".pending")
                try {
                    if (item.hasMotion) {
                        source.original(item).use { input -> staging.outputStream().use { input.copyTo(it) } }
                    } else {
                        val original = source.raster(item).use { it.readBytes() }
                        val encoded = withContext(dispatchers.conversion) { encode(original, rendition) }
                        staging.writeBytes(encoded)
                    }
                    check(staging.renameTo(file)) { "Unable to save export" }
                } finally { staging.delete() }
            }
        }
        ExportedMedia(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), mime, file)
    }
    suspend fun mp4(item: MediaItem): ExportedMedia = withContext(dispatchers.io) {
        require(item.mimeType == "video/webm")
        val original = prepare(item, Rendition.MEDIA)
        val file = File(original.file.parentFile, "${item.id}-compatible.mp4")
        mutex.withLock {
            if (!file.isFile) {
                val staging = File(file.parentFile, file.name + ".pending")
                try {
                    transcoder.mp4(original.file, staging)
                    check(staging.isFile && staging.length() > 0 && staging.renameTo(file))
                } finally { staging.delete() }
            }
        }
        ExportedMedia(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), "video/mp4", file)
    }
    private fun encode(original: ByteArray, rendition: Rendition): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Unable to decode image")
        val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            canvas.drawColor(if (rendition == Rendition.MEDIA) Color.WHITE else Color.TRANSPARENT)
            val scale = 496f / maxOf(bitmap.width, bitmap.height)
            val width = bitmap.width * scale; val height = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, RectF((512-width)/2, (512-height)/2,
                (512+width)/2, (512+height)/2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val format = when (rendition) {
                Rendition.MEDIA -> Bitmap.CompressFormat.JPEG
                Rendition.STICKER_PNG -> Bitmap.CompressFormat.PNG
                Rendition.STICKER_WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
            }
            var quality = 90
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { check(result.compress(format, quality, it)); it.toByteArray() }
                quality -= 10
            } while (rendition == Rendition.STICKER_WEBP && bytes.size > 100 * 1024 && quality >= 10)
            require(rendition != Rendition.STICKER_WEBP || bytes.size <= 100 * 1024) { "Sticker exceeds 100 KB" }
            return bytes
        } finally { result.recycle(); bitmap.recycle() }
    }

}
