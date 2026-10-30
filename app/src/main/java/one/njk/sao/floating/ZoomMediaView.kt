package one.njk.sao.floating

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.exifinterface.media.ExifInterface
import android.view.*
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.allowHardware
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.*

/** Full-resolution stills are decoded by visible region, never as an unbounded full-size bitmap. */
internal class ZoomMediaView(context: Context) : View(context) {
    data class Position(val zoom: Float = 1f, val x: Float = .5f, val y: Float = .5f)
    var position = Position()
    private var imageWidth = 0
    private var imageHeight = 0
    private val orientation = Matrix()
    private var orientedWidth = 0f
    private var orientedHeight = 0f
    private val transform = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var base: Bitmap? = null
    private var detail: Bitmap? = null
    private var detailRegion = Rect()
    private var drawable: Drawable? = null
    private var decoder: BitmapRegionDecoder? = null
    private var work: CoroutineScope? = null
    private var decoding: Job? = null
    private var requested: Pair<Rect, Int>? = null
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom(position.zoom * detector.scaleFactor, detector.focusX, detector.focusY); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onScroll(first: MotionEvent?, event: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaling.isInProgress && fit() > 0) {
                val scale = fit() * position.zoom
                position = position.copy(x = position.x + dx / (orientedWidth * scale), y = position.y + dy / (orientedHeight * scale))
                update()
            }
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            zoom(if (position.zoom > 1.05f) 1f else max(2f, 1f / fit()), event.x, event.y); return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
    })
    suspend fun show(file: File, loader: ImageLoader, mime: String, ready: () -> Unit): Unit = coroutineScope {
        work = this
        try {
            withContext(serial) {
                if(mime=="image/svg+xml") {
                    val svg=file.inputStream().use(com.caverock.androidsvg.SVG::getFromInputStream)
                    drawable=android.graphics.drawable.PictureDrawable(svg.renderToPicture())
                } else decoder = runCatching { BitmapRegionDecoder.newInstance(file.absolutePath, false) }.getOrNull()
                decoder?.let { region ->
                    imageWidth = region.width; imageHeight = region.height
                    val exif = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
                    val values = when(exif) {
                        2 -> floatArrayOf(-1f,0f,imageWidth.toFloat(),0f,1f,0f,0f,0f,1f)
                        3 -> floatArrayOf(-1f,0f,imageWidth.toFloat(),0f,-1f,imageHeight.toFloat(),0f,0f,1f)
                        4 -> floatArrayOf(1f,0f,0f,0f,-1f,imageHeight.toFloat(),0f,0f,1f)
                        5 -> floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f)
                        6 -> floatArrayOf(0f,-1f,imageHeight.toFloat(),1f,0f,0f,0f,0f,1f)
                        7 -> floatArrayOf(0f,-1f,imageHeight.toFloat(),-1f,0f,imageWidth.toFloat(),0f,0f,1f)
                        8 -> floatArrayOf(0f,1f,0f,-1f,0f,imageWidth.toFloat(),0f,0f,1f)
                        else -> floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
                    }
                    orientation.setValues(values)
                    var sample = 1
                    while(imageWidth.toLong() * imageHeight / sample / sample > 2_000_000) sample *= 2
                    base = region.decodeRegion(Rect(0,0,imageWidth,imageHeight), BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
            if (decoder == null) {
                if(drawable==null) {
                    val result = loader.execute(ImageRequest.Builder(context).data(file).size(Size.ORIGINAL).allowHardware(false).build())
                    drawable = (result as? SuccessResult)?.image?.asDrawable(resources) ?: throw java.io.IOException("Image unavailable")
                }
                imageWidth = requireNotNull(drawable).intrinsicWidth.coerceAtLeast(1)
                imageHeight = requireNotNull(drawable).intrinsicHeight.coerceAtLeast(1)
                orientation.reset()
                drawable?.callback = this@ZoomMediaView
                (drawable as? Animatable)?.start()
            }
            val bounds = RectF(0f,0f,imageWidth.toFloat(),imageHeight.toFloat()).also(orientation::mapRect)
            orientedWidth = bounds.width(); orientedHeight = bounds.height()
            update(); ready(); awaitCancellation()
        } finally {
            decoding?.cancelAndJoin(); decoding = null; work = null
            (drawable as? Animatable)?.stop(); drawable?.callback = null; drawable = null
            withContext(NonCancellable + serial) { decoder?.recycle(); decoder = null }
            base = null; detail = null; requested = null
        }
    }
    private fun fit() = if (orientedWidth > 0 && width > 0 && height > 0)
        min(width / orientedWidth, height / orientedHeight) else 0f
    private fun zoom(value: Float, x: Float, y: Float) {
        val fit = fit(); if (fit == 0f) return
        val old = position.zoom
        val next = value.coerceIn(1f, max(8f,1f / fit))
        position = position.copy(zoom=next,
            x=position.x+(x-width/2f)/orientedWidth/fit*(1/old-1/next),
            y=position.y+(y-height/2f)/orientedHeight/fit*(1/old-1/next))
        update()
    }
    private fun update() {
        val scale = fit() * position.zoom; if (scale <= 0) return
        fun center(c: Float, length: Float, viewport: Int) = if (length <= viewport) .5f else
            c.coerceIn(viewport / (2 * length), 1 - viewport / (2 * length))
        position = position.copy(x=center(position.x,orientedWidth*scale,width), y=center(position.y,orientedHeight*scale,height))
        transform.set(orientation)
        transform.postScale(scale,scale)
        transform.postTranslate(width/2f-position.x*orientedWidth*scale,height/2f-position.y*orientedHeight*scale)
        invalidate()
        if (decoder == null) return
        val inverse = Matrix(); if (!transform.invert(inverse)) return
        val visible = RectF(-32f,-32f,width+32f,height+32f).also(inverse::mapRect)
        val region = Rect(floor(visible.left).toInt().coerceIn(0,imageWidth),floor(visible.top).toInt().coerceIn(0,imageHeight),
            ceil(visible.right).toInt().coerceIn(0,imageWidth),ceil(visible.bottom).toInt().coerceIn(0,imageHeight))
        if (region.isEmpty) return
        var sample = 1
        while(sample * 2 * scale <= 1f || region.width().toLong() * region.height() / sample / sample > 4_000_000) sample *= 2
        val request = region to sample
        if(requested == request) return
        requested = request; decoding?.cancel()
        decoding = work?.launch {
            val decoded = withContext(serial) { ensureActive(); decoder?.decodeRegion(region,BitmapFactory.Options().apply { inSampleSize=sample }) }
            ensureActive()
            detail = decoded; detailRegion = region; invalidate()
        }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); update() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.save(); canvas.concat(transform)
        base?.let { canvas.drawBitmap(it,null,Rect(0,0,imageWidth,imageHeight),paint) }
        drawable?.let { it.setBounds(0,0,imageWidth,imageHeight); it.draw(canvas) }
        detail?.let { canvas.drawBitmap(it,null,detailRegion,paint) }
        canvas.restore()
    }
    override fun verifyDrawable(who: Drawable) = who === drawable || super.verifyDrawable(who)
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean { scaling.onTouchEvent(event); gestures.onTouchEvent(event); return true }
    override fun performClick(): Boolean { super.performClick(); return true }
}
