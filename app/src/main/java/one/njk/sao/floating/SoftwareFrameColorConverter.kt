package one.njk.sao.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.ScaleAndRotateTransformation
import kotlinx.coroutines.ensureActive
import one.njk.sao.media.NativeDecoder
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** One software frame at a time; Media3 owns display geometry, wide color and HDR tone mapping. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SoftwareFrameColorConverter(context: Context, private val info: NativeDecoder.Info, private val maxPixels: Long) : Closeable {
    private val ready = ConditionVariable()
    private val registered = ConditionVariable()
    private val failure = AtomicReference<Throwable?>()
    private val outputReady = ConditionVariable()
    private val output = AtomicReference<ImageReader?>()
    val width get() = requireNotNull(output.get()).width
    val height get() = requireNotNull(output.get()).height
    private val callbacks = HandlerThread("SAO software color frames", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private var processor: VideoFrameProcessor? = null
    init {
        try {
            processor = DefaultVideoFrameProcessor.Factory.Builder().build().create(context, DebugViewProvider.NONE,
                ColorInfo.SDR_BT709_LIMITED.buildUpon().setColorRange(C.COLOR_RANGE_FULL).build(), true, Runnable::run,
                object : VideoFrameProcessor.Listener {
                    override fun onInputStreamRegistered(type: Int, format: Format, effects: List<Effect>) { registered.open() }
                    override fun onError(exception: VideoFrameProcessingException) { failure.set(exception); registered.open(); ready.open(); outputReady.open() }
                    override fun onOutputSizeChanged(width: Int, height: Int) {
                        if (output.get() != null) return
                        try {
                            if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels)
                                throw VideoFrameDecoder.DecodeException(VideoFrameDecoder.Failure.MEMORY)
                            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                            output.set(reader)
                            reader.setOnImageAvailableListener({ ready.open() }, Handler(callbacks.looper))
                            requireNotNull(processor).setOutputSurfaceInfo(SurfaceInfo(reader.surface, width, height))
                        } catch (error: Throwable) { failure.set(error) }
                        outputReady.open()
                    }
                }).also { pipeline ->
                    processor = pipeline
                    val colors = ColorInfo.Builder().setColorSpace(if (info.colorPrimaries == 9) C.COLOR_SPACE_BT2020 else C.COLOR_SPACE_BT709)
                        .setColorRange(C.COLOR_RANGE_FULL)
                        .setColorTransfer(when (info.colorTransfer) {
                            18 -> C.COLOR_TRANSFER_HLG; 16 -> C.COLOR_TRANSFER_ST2084; else -> C.COLOR_TRANSFER_SDR
                        }).build()
                    val effects = if (info.rotationDegrees == 0f) emptyList() else
                        listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(-info.rotationDegrees).build())
                    pipeline.registerInputStream(VideoFrameProcessor.INPUT_TYPE_BITMAP,
                        Format.Builder().setWidth(info.width).setHeight(info.height).setPixelWidthHeightRatio(info.pixelWidthHeightRatio)
                            .setColorInfo(colors).build(), effects, 0)
                }
        } catch (error: Throwable) { close(); throw error }
    }
    fun convert(rgba: ByteBuffer, timeUs: Long, cancellation: CoroutineContext, consume: (Int, ByteBuffer) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!registered.block(50)) check(cancellation, deadline)
        check(cancellation, deadline)
        val bitmap = Bitmap.createBitmap(info.width, info.height,
            if (info.bytesPerPixel == 8) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888, true,
            ColorSpace.get(if (info.bytesPerPixel == 8) ColorSpace.Named.EXTENDED_SRGB else ColorSpace.Named.SRGB))
        var queued = false
        try {
            bitmap.setPremultiplied(false)
            rgba.rewind(); bitmap.copyPixelsFromBuffer(rgba)
            while (!queued) {
                check(cancellation, deadline)
                queued = requireNotNull(processor).queueInputBitmap(bitmap, SingleTimestamp(timeUs))
                if (!queued) Thread.sleep(1)
            }
            while (!outputReady.block(50)) check(cancellation, deadline)
            check(cancellation, deadline)
            val reader = requireNotNull(output.get())
            var image = reader.acquireNextImage()
            while (image == null) {
                ready.close(); check(cancellation, deadline); ready.block(50)
                image = reader.acquireNextImage()
            }
            image.use {
                val plane = it.planes.single()
                if (plane.pixelStride != 4) throw VideoFrameDecoder.DecodeException(VideoFrameDecoder.Failure.FORMAT)
                consume(plane.rowStride, plane.buffer)
            }
        } finally { if (!queued) bitmap.recycle() } // The pipeline owns accepted bitmaps.
    }
    private fun check(cancellation: CoroutineContext, deadline: Long) {
        cancellation.ensureActive()
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Color conversion cancelled")
        failure.get()?.let { if (it is VideoFrameDecoder.DecodeException) throw it else throw VideoFrameDecoder.DecodeException(VideoFrameDecoder.Failure.FORMAT, it) }
        if (SystemClock.elapsedRealtime() > deadline) throw VideoFrameDecoder.DecodeException(VideoFrameDecoder.Failure.FORMAT)
    }
    override fun close() {
        try { processor?.release() } finally {
            try { output.getAndSet(null)?.close() } finally {
                callbacks.quitSafely()
                var interrupted = Thread.interrupted()
                while (callbacks.isAlive) try { callbacks.join() } catch (_: InterruptedException) { interrupted = true }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
    private class SingleTimestamp(private val time: Long) : TimestampIterator {
        private var pending = true
        override fun hasNext() = pending
        override fun next(): Long { check(pending); pending = false; return time }
        override fun copyOf(): TimestampIterator = SingleTimestamp(time)
        override fun getLastTimestampUs() = time
    }
}
