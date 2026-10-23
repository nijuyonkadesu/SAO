package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.Clock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import one.njk.sao.R

/** One explicit export, no idle worker, polling, network or wake lock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoTranscoder @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers) {
    /** A byte target is supplied only after a receiving-app warning and an explicit choice. */
    suspend fun mp4(input: File, output: File, maxBytes: Long? = null): ExportResult = withContext(dispatchers.io) {
        val original = inspect(input)
        if(maxBytes==null) return@withContext export(input,output,original,null)
        require(maxBytes>0)
        if(original.durationUs<=0) throw MediaExportException(R.string.media_export_size_unavailable)
        val audio = if(!original.hasAudio) 0 else if(original.audioMime==MimeTypes.AUDIO_AAC)
            original.audioBitrate?.takeIf { it>0 } ?: 131_072 else 131_072
        var bitrate=(maxBytes*8.0*.92*1_000_000/original.durationUs-audio).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        repeat(2) {
            if(bitrate<16_000) throw MediaExportException(R.string.media_export_size_unavailable)
            output.delete()
            val result=export(input,output,original,bitrate)
            if(output.length()<=maxBytes) return@withContext result
            val audioBytes=(result.averageAudioBitrate.takeIf { it>0 } ?: audio)*original.durationUs/8_000_000.0
            bitrate=(bitrate*((maxBytes-audioBytes-1024)/(output.length()-audioBytes).coerceAtLeast(1.0))*.9).toInt()
        }
        throw MediaExportException(R.string.media_export_size_unavailable)
    }

    private suspend fun export(input: File, output: File, original: VideoInfo, bitrate: Int?): ExportResult {
        val videoMime = if (original.hdr) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val thread = HandlerThread("SAO video export").apply { start() }
        val handler = Handler(thread.looper)
        val worker = handler.asCoroutineDispatcher("SAO video export")
        var transformer: Transformer? = null
        return try {
            withContext(worker) {
                suspendCancellableCoroutine<ExportResult> { continuation ->
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (!continuation.isActive) return
                            if (original.hdr && (!ColorInfo.isTransferHdr(exportResult.colorInfo) ||
                                    exportResult.colorInfo?.colorTransfer != original.transfer))
                                continuation.resumeWithException(MediaExportException(R.string.media_export_hdr_unavailable))
                            else continuation.resume(exportResult)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(MediaExportException(when(exportException.errorCode) {
                                ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FAILED,
                                ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> R.string.media_export_decode_failed
                                ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FAILED,
                                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                                    if (original.hdr) R.string.media_export_hdr_unavailable else R.string.media_export_encode_failed
                                ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
                                    if (original.hdr) R.string.media_export_hdr_unavailable else R.string.media_export_encode_failed
                                else -> R.string.media_export_failed
                            },exportException))
                        }
                        override fun onFallbackApplied(composition: Composition, request: TransformationRequest, fallback: TransformationRequest) {
                            if (original.hdr && fallback.hdrMode != Composition.HDR_MODE_KEEP_HDR && continuation.isActive) {
                                continuation.resumeWithException(MediaExportException(R.string.media_export_hdr_unavailable))
                                transformer?.cancel()
                            }
                        }
                    }
                    val decoder = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build()
                    val encoder = DefaultEncoderFactory.Builder(context)
                        .setEnableFormatFallback(false)
                        .apply { if(bitrate!=null) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()) }
                        .build()
                    val created = Transformer.Builder(context).setLooper(thread.looper)
                        .setVideoMimeType(videoMime).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        // Fast-start reservation can outweigh a short clip. Budgeted copies are
                        // local attachments: keep those bytes for media instead of reserved space.
                        .setMuxerFactory(InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(bitrate==null))
                        .setAssetLoaderFactory(DefaultAssetLoaderFactory(context,decoder,Clock.DEFAULT,null))
                        .setEncoderFactory(encoder)
                        .addListener(listener).build()
                    transformer = created
                    continuation.invokeOnCancellation { handler.post { created.cancel() } }
                    created.start(EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(input))).build(), output.path)
                }
            }.also { result ->
                currentCoroutineContext().ensureActive()
                val actual = inspect(output)
                if (result.videoMimeType != videoMime || actual.mime != videoMime ||
                    result.width <= 0 || result.height <= 0 || output.length() <= 0 ||
                    !((actual.width==original.width && actual.height==original.height) ||
                        (actual.width==original.height && actual.height==original.width)) ||
                    (original.hasAudio && actual.audioMime != MimeTypes.AUDIO_AAC))
                    throw MediaExportException(R.string.media_export_failed)
                if (original.hdr && (!actual.hdr || actual.transfer != original.transfer))
                    throw MediaExportException(R.string.media_export_hdr_unavailable)
            }
        } finally {
            withContext(NonCancellable + worker) { transformer?.cancel() }
            thread.quitSafely()
        }
    }

    internal data class VideoInfo(val mime: String, val width: Int, val height: Int,
        val transfer: Int?, val audioMime: String?, val durationUs: Long, val audioBitrate: Int?) {
        val hdr get() = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
        val hasAudio get() = audioMime != null
    }
    internal fun inspect(file: File): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            var video: MediaFormat? = null
            var audio: String? = null
            var audioBitrate: Int? = null
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video == null) video = format
                if (mime.startsWith("audio/") && audio == null) {
                    audio = mime
                    audioBitrate=format.takeIf { it.containsKey(MediaFormat.KEY_BIT_RATE) }?.getInteger(MediaFormat.KEY_BIT_RATE)
                }
            }
            val format = video ?: throw MediaExportException(R.string.media_export_decode_failed)
            return VideoInfo(format.getString(MediaFormat.KEY_MIME).orEmpty(),format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT),format.takeIf { it.containsKey(MediaFormat.KEY_COLOR_TRANSFER) }
                    ?.getInteger(MediaFormat.KEY_COLOR_TRANSFER),audio,
                format.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION) ?: 0,audioBitrate)
        } catch(error: java.io.IOException) { throw MediaExportException(R.string.media_export_decode_failed,error) }
        catch(error: IllegalArgumentException) { throw MediaExportException(R.string.media_export_decode_failed,error) }
        finally { extractor.release() }
    }
}

class MediaExportException(val messageRes: Int, cause: Throwable? = null) : Exception("Media export failed", cause)

/** A capability rejection happens before dispatch. An uncertain send result must never be retried. */
internal object VideoCompatibility {
    fun mime(original: String, accepts: (String) -> Boolean): String =
        if (original == "video/webm" && !accepts(original) && accepts("video/mp4")) "video/mp4" else original
}
