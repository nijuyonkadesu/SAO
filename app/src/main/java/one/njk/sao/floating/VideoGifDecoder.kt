package one.njk.sao.floating

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.ScaleAndRotateTransformation
import kotlinx.coroutines.ensureActive
import one.njk.sao.R
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Sequential platform decoding, with Media3 color conversion/tone mapping and one RGBA image in flight. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoGifDecoder(private val context: Context) {
    data class Result(val width: Int,val height: Int,val durationUs: Long)
    fun decode(input: File,cancellation: CoroutineContext,
        frame: (index: Int,width: Int,height: Int,rowBytes: Int,rgba: ByteBuffer,ptsUs: Long)->Unit): Result {
        val extractor=MediaExtractor()
        var decoder: MediaCodec?=null
        var processor: VideoFrameProcessor?=null
        var reader: ImageReader?=null
        var callbacks: HandlerThread?=null
        var started=false
        try {
            extractor.setDataSource(input.path)
            val track=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
            } ?: throw MediaExportException(R.string.media_export_decode_failed)
            extractor.selectTrack(track)
            val mediaFormat=extractor.getTrackFormat(track)
            val format=MediaFormatUtil.createFormatFromMediaFormat(mediaFormat)
            val rotation=((format.rotationDegrees%360)+360)%360
            if(rotation%90!=0 || format.pixelWidthHeightRatio!=1f)
                throw MediaExportException(R.string.media_export_gif_unavailable)
            val width=if(rotation==90 || rotation==270) format.height else format.width
            val height=if(rotation==90 || rotation==270) format.width else format.height
            // Gifski's lookahead and quantization need several full-size native frames.
            // Refuse an unsafe full-resolution conversion rather than downsampling it.
            if(width<=0 || height<=0 || width>65535 || height>65535 ||
                width.toLong()*height>Runtime.getRuntime().maxMemory()/128)
                throw MediaExportException(R.string.media_export_gif_memory)
            val declared=format.colorInfo
            // Containers may declare only one color field. Fill absent SDR defaults
            // without discarding any provided primaries, transfer or HDR metadata.
            val colors=(declared ?: ColorInfo.SDR_BT709_LIMITED).buildUpon()
                .setColorSpace(declared?.colorSpace?.takeIf { it!=androidx.media3.common.Format.NO_VALUE } ?: C.COLOR_SPACE_BT709)
                .setColorRange(declared?.colorRange?.takeIf { it!=androidx.media3.common.Format.NO_VALUE } ?: C.COLOR_RANGE_LIMITED)
                .setColorTransfer(declared?.colorTransfer?.takeIf { it!=androidx.media3.common.Format.NO_VALUE } ?: C.COLOR_TRANSFER_SDR).build()
            // Media3's external sampler uses SDR (Android's sRGB-equivalent path).
            // HDR input gets its supported BT.2020 -> BT.709 GL tone mapping here.
            val outputColors=ColorInfo.SDR_BT709_LIMITED.buildUpon().setColorRange(C.COLOR_RANGE_FULL).build()
            val ready=ConditionVariable()
            val streamReady=ConditionVariable()
            val error=AtomicReference<VideoFrameProcessingException?>()
            callbacks=HandlerThread("SAO GIF frames",android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            val images=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2).also { reader=it }
            images.setOnImageAvailableListener({ ready.open() },Handler(callbacks.looper))
            val pipeline=DefaultVideoFrameProcessor.Factory.Builder().build().create(context,DebugViewProvider.NONE,
                outputColors,true,Runnable::run,object: VideoFrameProcessor.Listener {
                    override fun onInputStreamRegistered(type: Int,format: androidx.media3.common.Format,effects: List<androidx.media3.common.Effect>) { streamReady.open() }
                    override fun onError(exception: VideoFrameProcessingException) { error.set(exception); ready.open(); streamReady.open() }
                }).also { processor=it }
            pipeline.setOutputSurfaceInfo(SurfaceInfo(images.surface,width,height))
            val effects=if(rotation==0) emptyList() else listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(-rotation.toFloat()).build())
            pipeline.registerInputStream(VideoFrameProcessor.INPUT_TYPE_SURFACE,
                format.buildUpon().setRotationDegrees(0).setColorInfo(colors).build(),effects,0)
            val initialDeadline=SystemClock.elapsedRealtime()+FRAME_TIMEOUT_MS
            while(!streamReady.block(50)) {
                cancellation.ensureActive()
                if(SystemClock.elapsedRealtime()>initialDeadline) throw MediaExportException(R.string.media_export_gif_unavailable)
            }
            error.get()?.let { throw MediaExportException(R.string.media_export_gif_unavailable,it) }
            // The GL pipeline owns rotation, including devices whose decoder rotates surfaces.
            mediaFormat.setInteger(MediaFormat.KEY_ROTATION,0)
            decoder=MediaCodec.createDecoderByType(requireNotNull(mediaFormat.getString(MediaFormat.KEY_MIME)))
            decoder.configure(mediaFormat,pipeline.inputSurface,null,0)
            decoder.start(); started=true
            val info=MediaCodec.BufferInfo()
            var inputEnded=false; var outputEnded=false
            var frames=0; var firstUs: Long?=null; var previousUs=Long.MIN_VALUE
            var deadline=SystemClock.elapsedRealtime()+FRAME_TIMEOUT_MS
            while(!outputEnded) {
                cancellation.ensureActive()
                error.get()?.let { throw MediaExportException(R.string.media_export_gif_unavailable,it) }
                if(SystemClock.elapsedRealtime()>deadline) throw MediaExportException(R.string.media_export_decode_failed)
                if(!inputEnded) {
                    val slot=decoder.dequeueInputBuffer(0)
                    if(slot>=0) {
                        val buffer=requireNotNull(decoder.getInputBuffer(slot))
                        val size=extractor.readSampleData(buffer,0)
                        if(size<0) {
                            decoder.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded=true
                        } else {
                            decoder.queueInputBuffer(slot,0,size,extractor.sampleTime,0); extractor.advance()
                        }
                    }
                }
                val slot=decoder.dequeueOutputBuffer(info,10_000)
                if(slot<0) continue
                val render=info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0
                outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                if(render) {
                    if(info.presentationTimeUs<=previousUs) throw MediaExportException(R.string.media_export_gif_unavailable)
                    if(!pipeline.registerInputFrame()) throw MediaExportException(R.string.media_export_gif_unavailable)
                    decoder.releaseOutputBuffer(slot,true)
                    val frameDeadline=SystemClock.elapsedRealtime()+FRAME_TIMEOUT_MS
                    var image=images.acquireNextImage()
                    while(image==null) {
                        ready.close()
                        cancellation.ensureActive()
                        error.get()?.let { throw MediaExportException(R.string.media_export_gif_unavailable,it) }
                        if(SystemClock.elapsedRealtime()>frameDeadline) throw MediaExportException(R.string.media_export_gif_unavailable)
                        // A timed wait also covers an image arriving between acquire and close.
                        ready.block(50); image=images.acquireNextImage()
                    }
                    image.use {
                        val plane=it.planes.single()
                        if(it.width!=width || it.height!=height || plane.pixelStride!=4)
                            throw MediaExportException(R.string.media_export_gif_unavailable)
                        if(firstUs==null) firstUs=info.presentationTimeUs
                        frame(frames++,width,height,plane.rowStride,plane.buffer,info.presentationTimeUs-firstUs)
                    }
                    previousUs=info.presentationTimeUs
                    deadline=SystemClock.elapsedRealtime()+FRAME_TIMEOUT_MS
                } else decoder.releaseOutputBuffer(slot,false)
            }
            if(frames==0) throw MediaExportException(R.string.media_export_decode_failed)
            pipeline.signalEndOfInput()
            val duration=MediaFormatUtil.getInteger(mediaFormat,MediaFormat.KEY_FRAME_RATE,0).takeIf { it>0 }?.let { 1_000_000L/it } ?: 10_000
            val end=if(mediaFormat.containsKey(MediaFormat.KEY_DURATION)) mediaFormat.getLong(MediaFormat.KEY_DURATION)
                else previousUs+duration
            return Result(width,height,(end-(firstUs ?: 0)).coerceAtLeast(previousUs-(firstUs ?: 0)+10_000))
        } catch(error: MediaExportException) { throw error }
        catch(error: java.io.IOException) { throw MediaExportException(R.string.media_export_decode_failed,error) }
        catch(error: IllegalArgumentException) { throw MediaExportException(R.string.media_export_gif_unavailable,error) }
        catch(error: IllegalStateException) { throw MediaExportException(R.string.media_export_gif_unavailable,error) }
        finally {
            try { if(started) decoder?.stop() } finally {
                try { decoder?.release() } finally {
                    try { processor?.release() } finally {
                        reader?.close(); callbacks?.quitSafely(); callbacks?.join(); extractor.release()
                    }
                }
            }
        }
    }
    companion object { private const val FRAME_TIMEOUT_MS=15_000 }
}
