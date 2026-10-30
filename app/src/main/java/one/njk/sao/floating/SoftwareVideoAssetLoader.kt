package one.njk.sao.floating

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableMap
import one.njk.sao.media.NativeDecoder
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Software frames enter the existing Media3 encoder as shared textures, without an intermediate file. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SoftwareVideoAssetLoader private constructor(
    private val context: Context, private val input: File, private val item: EditedMediaItem,
    private val listener: AssetLoader.Listener, private val owner: Factory
) : AssetLoader {
    /** Own this factory on the Transformer's looper and close it after cancelling the Transformer. */
    class Factory(private val context: Context, private val input: File,
        private val audioLoader: AssetLoader.Factory) : AssetLoader.Factory, Closeable {
        private val display = GlUtil.getDefaultEglDisplay()
        private val shared = try {
            GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        } catch (error: Exception) { GlUtil.terminate(display); throw error }
        val frames: DefaultVideoFrameProcessor.Factory = DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(DefaultGlObjectsProvider(shared)).build()
        override fun createAssetLoader(item: EditedMediaItem, looper: android.os.Looper,
            listener: AssetLoader.Listener, settings: AssetLoader.CompositionSettings): AssetLoader =
            if (item.removeVideo) audioLoader.createAssetLoader(item, looper, listener, settings)
            else SoftwareVideoAssetLoader(context, input, item, listener, this)
        override fun close() {
            try { GlUtil.destroyEglContext(display, shared) } finally { GlUtil.terminate(display) }
        }
        fun createContext() = GlUtil.createEglContext(shared, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        fun display() = display
    }

    @Volatile private var stopped = false
    @Volatile private var delegate: RawAssetLoader? = null
    private val processed = ArrayBlockingQueue<Long>(1)
    private var worker: Thread? = null
    override fun start() {
        check(worker == null)
        worker = Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); decode() }, "SAO software export").apply { start() }
    }
    override fun getProgress(holder: ProgressHolder) = delegate?.getProgress(holder) ?: Transformer.PROGRESS_STATE_NOT_STARTED
    override fun getDecoderNames(): ImmutableMap<Int, String> = ImmutableMap.of(C.TRACK_TYPE_VIDEO, "SAO software decoder")
    override fun release() {
        stopped = true
        worker?.interrupt()
        var interrupted = Thread.interrupted()
        while (worker?.isAlive == true) try { worker?.join() } catch (_: InterruptedException) { interrupted = true }
        delegate?.release()
        if (interrupted) Thread.currentThread().interrupt()
    }
    private fun active(deadline: Long) {
        if (stopped || Thread.currentThread().isInterrupted) throw InterruptedException()
        check(SystemClock.elapsedRealtime() < deadline) { "Software export frame deadline" }
    }
    private fun decode() {
        val display = owner.display()
        var egl: android.opengl.EGLContext? = null
        var surface: android.opengl.EGLSurface? = null
        var texture = 0
        try {
            NativeDecoder.open(context, input, Runtime.getRuntime().maxMemory() / 128,
                BooleanSupplier { stopped || Thread.currentThread().isInterrupted }).use { decoder ->
                val info = decoder.info
                val colors = ColorInfo.Builder().setColorSpace(if (info.colorPrimaries == 9) C.COLOR_SPACE_BT2020 else C.COLOR_SPACE_BT709)
                    .setColorRange(C.COLOR_RANGE_FULL).setColorTransfer(when (info.colorTransfer) {
                        18 -> C.COLOR_TRANSFER_HLG; 16 -> C.COLOR_TRANSFER_ST2084; else -> C.COLOR_TRANSFER_SDR
                    }).build()
                val format = Format.Builder().setWidth(info.width).setHeight(info.height)
                    .setRotationDegrees(info.rotationDegrees.toInt()).setPixelWidthHeightRatio(info.pixelWidthHeightRatio)
                    .setColorInfo(colors).build()
                val loader = RawAssetLoader(item, listener, null, format) { _, fence ->
                    if (stopped) GlUtil.deleteSyncObjectQuietly(fence)
                    else if (!processed.offer(fence)) GlUtil.deleteSyncObjectQuietly(fence)
                }.also { delegate = it }
                egl = owner.createContext()
                surface = GlUtil.createFocusedPlaceholderEglSurface(requireNotNull(egl), display)
                texture = GlUtil.createTexture(info.width, info.height, info.bytesPerPixel == 8)
                val rgba = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(info.width, info.height), info.bytesPerPixel))
                loader.start()
                while (!stopped) {
                    rgba.clear()
                    if (!decoder.next(rgba)) break
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, info.width, info.height,
                        GLES20.GL_RGBA, if (info.bytesPerPixel == 8) GLES30.GL_HALF_FLOAT else GLES20.GL_UNSIGNED_BYTE, rgba)
                    GlUtil.checkGlError()
                    // The consumer runs in another shared EGL context. Publish this upload before queueing.
                    GLES20.glFinish()
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    while (!loader.queueInputTexture(texture, decoder.presentationTimeUs)) {
                        active(deadline); Thread.sleep(5)
                    }
                    var fence = processed.poll(50, TimeUnit.MILLISECONDS)
                    while (fence == null) { active(deadline); fence = processed.poll(50, TimeUnit.MILLISECONDS) }
                    GlUtil.awaitSyncObject(fence)
                    GlUtil.deleteSyncObjectQuietly(fence)
                }
                if (!stopped) loader.signalEndOfVideoInput()
            }
        } catch (error: Exception) {
            if (!stopped) listener.onError(ExportException.createForAssetLoader(error, ExportException.ERROR_CODE_DECODING_FAILED))
        } finally {
            processed.poll()?.let(GlUtil::deleteSyncObjectQuietly)
            try { if (texture != 0) GlUtil.deleteTexture(texture) }
            finally {
                try { if (surface != null) GlUtil.destroyEglSurface(display, surface) }
                finally { if (egl != null) GlUtil.destroyEglContext(display, egl) }
            }
        }
    }
}
