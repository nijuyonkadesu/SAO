package one.njk.sao.floating

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.concurrent.Callable

/** Limits belong to the receiving sticker format, never to source uploads or normal media. */
data class StickerRequirements(val maxBytes: Int = 100 * 1024) {
    init { require(maxBytes > 0) }
    companion object {
        val WHATSAPP = StickerRequirements()
        val TELEGRAM = StickerRequirements(512 * 1024)
    }
}

/** Platform-only still conversion. The caller supplies a seekable file, not a full heap byte array. */
internal object StaticStickerEncoder {
    internal data class Decoded(val bitmap: Bitmap, val reusable: Boolean)

    internal fun decode(input: FileInputStream, rendition: Rendition, requirements: StickerRequirements): Decoded {
        input.channel.position(0)
        val source = ImageDecoder.createSource(Callable {
            AssetFileDescriptor(ParcelFileDescriptor.dup(input.fd), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        })
        var reusable = false
        var mime = ""
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            require(!info.isAnimated) { "Animated sticker conversion is unavailable" }
            mime = info.mimeType
            val formatMatches = info.mimeType == if (rendition == Rendition.STICKER_PNG) "image/png" else "image/webp"
            reusable = formatMatches && info.size.width == 512 && info.size.height == 512 &&
                (rendition == Rendition.STICKER_PNG || input.channel.size() <= requirements.maxBytes)
            val edge = if (reusable) 512 else 496
            val scale = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            // A square may still contain EXIF rotation/reflection. Bake that in rather than
            // copying metadata a receiving sticker renderer might ignore.
            val noOrientation = hasNoOrientationMetadata(input, mime)
            reusable = reusable && noOrientation
            return Decoded(bitmap, reusable)
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    /** Null means the original already satisfies the target and should be copied byte-for-byte. */
    suspend fun encode(input: FileInputStream, rendition: Rendition, requirements: StickerRequirements): ByteArray? {
        currentCoroutineContext().ensureActive()
        val decoded = decode(input, rendition, requirements)
        val image = decoded.bitmap
        try {
            currentCoroutineContext().ensureActive()
            if (decoded.reusable) return null
            val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                val scale = 496f / maxOf(image.width, image.height)
                val width = image.width * scale; val height = image.height * scale
                Canvas(result).apply {
                    drawColor(Color.TRANSPARENT)
                    drawBitmap(image, null, RectF((512-width)/2, (512-height)/2, (512+width)/2, (512+height)/2),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                val buffer = ByteArrayOutputStream()
                suspend fun compress(format: Bitmap.CompressFormat, quality: Int): ByteArray {
                    currentCoroutineContext().ensureActive()
                    buffer.reset()
                    check(result.compress(format, quality, buffer)) { "Image encoding failed" }
                    currentCoroutineContext().ensureActive()
                    return buffer.toByteArray()
                }
                if (rendition == Rendition.STICKER_PNG) return compress(Bitmap.CompressFormat.PNG, 100)
                // Lossless WebP's quality parameter controls encoding effort, not visual quality.
                val lossless = compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 40)
                if (lossless.size <= requirements.maxBytes) return lossless
                val highest = compress(Bitmap.CompressFormat.WEBP_LOSSY, 100)
                if (highest.size <= requirements.maxBytes) return highest
                var best = compress(Bitmap.CompressFormat.WEBP_LOSSY, 10)
                require(best.size <= requirements.maxBytes) { "Sticker exceeds the receiving app's size limit" }
                var low = 10; var high = 100
                // Only a real sticker byte limit permits lossy encoding. Keep a verified fitting
                // candidate; encoded sizes need not be strictly monotonic with quality.
                repeat(5) {
                    val quality = (low + high) / 2
                    val bytes = compress(Bitmap.CompressFormat.WEBP_LOSSY, quality)
                    if (bytes.size <= requirements.maxBytes) { low = quality; best = bytes } else high = quality
                }
                return best
            } finally { result.recycle() }
        } finally { image.recycle() }
    }

    private fun hasNoOrientationMetadata(input: FileInputStream, mime: String): Boolean {
        if (mime !in setOf("image/png", "image/webp")) return false
        val png = mime == "image/png"
        val channel = input.channel
        channel.position(if (png) 8 else 12)
        val data = DataInputStream(input)
        val length = channel.size()
        var noOrientation = true
        repeat(256) {
            if (channel.position() == length) return noOrientation
            if (length - channel.position() < 8) return false
            val size: Long
            val type: Int
            if (png) {
                size = data.readInt().toLong() and 0xffffffffL; type = data.readInt()
            } else {
                type = data.readInt(); size = Integer.reverseBytes(data.readInt()).toLong() and 0xffffffffL
            }
            // eXIf / EXIF, plus APNG/WebP animation markers as a second safeguard.
            require(type !in setOf(0x6163544c, 0x414e494d, 0x414e4d46)) { "Animated sticker conversion is unavailable" }
            if (type in setOf(0x65584966, 0x45584946)) noOrientation = false
            val end = channel.position() + size + if (png) 4 else size % 2
            if (end > length) return false
            channel.position(end)
        }
        return false
    }
}
