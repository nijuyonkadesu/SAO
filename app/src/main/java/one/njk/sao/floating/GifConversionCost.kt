package one.njk.sao.floating

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.ceil

/** Work estimate, not a promise about compressed size. No sample frames or network requests. */
data class GifConversionCost(val width: Int,val height: Int,val frames: Long) {
    init { require(width>0 && height>0 && frames>0) }
    val indexedBytes: Long get() = runCatching { Math.multiplyExact(Math.multiplyExact(width.toLong(),height.toLong()),frames) }.getOrDefault(Long.MAX_VALUE)
    val large get() = indexedBytes >= 32L*1024*1024
    companion object {
        internal fun inspect(input: File): GifConversionCost? {
            val extractor=MediaExtractor()
            return try {
                extractor.setDataSource(input.path)
                val format=(0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true } ?: return null
                val width=format.getInteger(MediaFormat.KEY_WIDTH); val height=format.getInteger(MediaFormat.KEY_HEIGHT)
                val duration=format.getLong(MediaFormat.KEY_DURATION)
                val rate=format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: return null
                if(width<=0 || height<=0 || duration<=0 || !rate.isFinite() || rate<=0) null
                else GifConversionCost(width,height,ceil(duration / 1_000_000.0 * rate).toLong().coerceAtLeast(1))
            } catch(_: java.io.IOException) { null }
            catch(_: RuntimeException) { null }
            finally { extractor.release() }
        }
    }
}
