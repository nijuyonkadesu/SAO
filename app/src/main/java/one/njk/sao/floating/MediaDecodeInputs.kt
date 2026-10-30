package one.njk.sao.floating

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import one.njk.sao.source.*
import java.io.File

/** Compatible renditions must not upscale the original; otherwise use local software decoding. */
internal object MediaDecodeInputs {
    private data class Dimensions(val width: Int, val height: Int) {
        fun fits(original: Dimensions) = width <= original.width && height <= original.height
    }
    private data class VideoInput(val dimensions: Dimensions?, val platformSupported: Boolean)

    fun platformSupports(file: File): Boolean = inspect(file)?.platformSupported == true

    private fun inspect(file: File): VideoInput? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return null
            VideoInput(dimensions(format),
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null)
        } catch (_: java.io.IOException) { null }
        catch (_: RuntimeException) { null }
        finally { extractor.release() }
    }

    private fun dimensions(format: MediaFormat): Dimensions? {
        val width = format.getInteger(MediaFormat.KEY_WIDTH, 0)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
        val rotation = Math.floorMod(format.getInteger(MediaFormat.KEY_ROTATION, 0), 360)
        val sarWidth = format.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, 1)
        val sarHeight = format.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, 1)
        // Unknown/non-square display geometry cannot prove a rendition is no larger.
        if (width <= 0 || height <= 0 || rotation % 90 != 0 || sarWidth <= 0 || sarWidth != sarHeight) return null
        return if (rotation % 180 == 0) Dimensions(width, height) else Dimensions(height, width)
    }

    suspend fun <T> withInput(source: MediaSource, item: MediaItem, directory: File,
        block: suspend (file: File, playback: Boolean) -> T): T =
        source.withRendition(item, Rendition.ORIGINAL) { original ->
            withLocalFile(original, directory) { file ->
                val revisions = item.remote?.cacheKeys.orEmpty()
                val same = revisions["original"] != null && revisions["original"] == revisions["video"]
                val originalInfo = if (item.isVideo && !same) inspect(file) else null
                val originalSize = originalInfo?.dimensions
                if (!item.isVideo || same || originalSize == null || originalInfo.platformSupported) block(file, false)
                else {
                    var used = false
                    try {
                        source.withRendition(item, Rendition.PLAYBACK) { compatible ->
                            withLocalFile(compatible, directory) { playback ->
                                val playbackInfo = if (playback != file) inspect(playback) else null
                                if (playbackInfo?.platformSupported == true && playbackInfo.dimensions?.fits(originalSize) == true) {
                                    used = true
                                    block(playback, true)
                                } else {
                                    used = true
                                    block(file, false)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (used) throw error
                        block(file, false)
                    }
                }
            }
        }

    suspend fun <T> withLocalFile(handle: MediaHandle, directory: File, block: suspend (File) -> T): T {
        handle.file?.let { return block(it) }
        val file=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            directory.mkdirs()
            File.createTempFile("decode-", ".pending", directory).also { file ->
                try { handle.openStream().use { input -> file.outputStream().use { MediaExportStore.copy(input,it) } } }
                catch(error: Throwable) { file.delete(); throw error }
            }
        }
        try { return block(file) }
        finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable+kotlinx.coroutines.Dispatchers.IO) { file.delete() } }
    }
}
