package one.njk.sao.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Muted, paced fallback; owns one visible surface and exists only while its source handle is held. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SoftwareVideoPreview {
    @androidx.annotation.MainThread
    suspend fun play(view: PlayerView, input: File, firstFrame: () -> Unit) {
        val child = view.videoSurfaceView
        val owned = child is TextureView
        val surface = withTimeout(3_000) { readySurface(child) }
        val opaque = (child as? TextureView)?.isOpaque
        (child as? TextureView)?.isOpaque = false
        view.setShutterBackgroundColor(Color.TRANSPARENT)
        val worker = HandlerThread("SAO software preview", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val dispatcher = Handler(worker.looper).asCoroutineDispatcher()
        try {
            withContext(dispatcher) {
                var shown = false
                val main = Handler(Looper.getMainLooper())
                val cancellation = currentCoroutineContext()
                while (true) {
                    ensureActive()
                    var bitmap: Bitmap? = null
                    var pixels = IntArray(0)
                    try {
                        var began: Long? = null
                        val result = VideoFrameDecoder(view.context).decodeSoftware(input, cancellation) { _, width, height, stride, rgba, time ->
                            ensureActive()
                            val start = began ?: (SystemClock.elapsedRealtime() - time / 1000).also { began = it }
                            val due = start + time / 1000
                            while (due > SystemClock.elapsedRealtime()) {
                                cancellation.ensureActive()
                                Thread.sleep(minOf(50, due - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                            }
                            if (bitmap == null) {
                                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                pixels = IntArray(width * height)
                            }
                            val bytes = rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            val origin = bytes.position()
                            for (y in 0 until height) for (x in 0 until width) {
                                val value = bytes.getInt(origin + y * stride + x * 4)
                                pixels[y * width + x] = (value and 0xff00ff00.toInt()) or
                                    ((value and 255) shl 16) or ((value ushr 16) and 255)
                            }
                            requireNotNull(bitmap).setPixels(pixels, 0, width, 0, 0, width, height)
                            // A raster Canvas copies the pixels before unlock, allowing one
                            // reusable bitmap without racing the UI/RenderThread's display list.
                            val canvas = surface.lockCanvas(null)
                            try { draw(canvas, requireNotNull(bitmap)) } finally { surface.unlockCanvasAndPost(canvas) }
                            if (!shown) { main.post { firstFrame() }; shown = true }
                        }
                        delay((requireNotNull(began) + result.durationUs / 1000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    } finally { bitmap?.recycle() }
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { worker.quitSafely(); worker.join() }
                if (opaque != null) (child as TextureView).isOpaque = opaque
                if (owned) surface.release()
            }
        }
    }
    private suspend fun readySurface(view: View?): Surface = when (view) {
        is TextureView -> {
            val texture = view.surfaceTexture ?: suspendCancellableCoroutine { continuation ->
                val previous = view.surfaceTextureListener
                lateinit var listener: TextureView.SurfaceTextureListener
                fun restore() { if (view.surfaceTextureListener === listener) view.surfaceTextureListener = previous }
                listener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        previous?.onSurfaceTextureAvailable(texture, width, height); restore()
                        if (continuation.isActive) continuation.resume(texture)
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        previous?.onSurfaceTextureSizeChanged(texture, width, height)
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { previous?.onSurfaceTextureUpdated(texture) }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        restore()
                        if (continuation.isActive) continuation.resumeWithException(java.io.IOException("Playback surface destroyed"))
                        return previous?.onSurfaceTextureDestroyed(texture) ?: true
                    }
                }
                view.surfaceTextureListener = listener
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { restore() } }
            }
            Surface(texture)
        }
        is SurfaceView -> if (view.holder.surface.isValid) view.holder.surface else suspendCancellableCoroutine { continuation ->
            val listener = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    holder.removeCallback(this)
                    if (continuation.isActive) continuation.resume(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    holder.removeCallback(this)
                    if (continuation.isActive) continuation.resumeWithException(java.io.IOException("Playback surface destroyed"))
                }
            }
            view.holder.addCallback(listener)
            continuation.invokeOnCancellation { view.holder.removeCallback(listener) }
        }
        else -> throw java.io.IOException("Software playback surface unavailable")
    }
    private fun draw(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val scale = minOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
        val width = bitmap.width * scale; val height = bitmap.height * scale
        val destination = RectF((canvas.width - width) / 2, (canvas.height - height) / 2,
            (canvas.width + width) / 2, (canvas.height + height) / 2)
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), destination, null)
    }
}
