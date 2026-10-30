package one.njk.sao.floating

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import one.njk.sao.source.*
import java.io.File

/** Chooses a source-owned compatible rendition before local software decoding. No upload/mutation. */
internal object MediaDecodeInputs {
    fun platformSupports(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            format != null && MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
        } catch (_: java.io.IOException) { false }
        catch (_: RuntimeException) { false }
        finally { extractor.release() }
    }

    suspend fun <T> withInput(source: MediaSource, item: MediaItem, directory: File,
        block: suspend (file: File, playback: Boolean) -> T): T =
        source.withRendition(item, Rendition.ORIGINAL) { original ->
            seekable(original, directory) { file ->
                val revisions = item.remote?.cacheKeys.orEmpty()
                val same = revisions["original"] != null && revisions["original"] == revisions["video"]
                if (!item.isVideo || same || platformSupports(file)) block(file, false)
                else {
                    var used = false
                    try {
                        source.withRendition(item, Rendition.PLAYBACK) { compatible ->
                            seekable(compatible, directory) { playback ->
                                if (playback != file && platformSupports(playback)) {
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

    private suspend fun <T> seekable(handle: MediaHandle, directory: File, block: suspend (File) -> T): T {
        handle.file?.let { return block(it) }
        directory.mkdirs()
        val file = File.createTempFile("decode-", ".pending", directory)
        try {
            handle.openStream().use { input -> file.outputStream().use { MediaExportStore.copy(input, it) } }
            return block(file)
        } finally { file.delete() }
    }
}
