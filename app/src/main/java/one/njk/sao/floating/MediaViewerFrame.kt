package one.njk.sao.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.TextureView
import android.view.View
import kotlin.math.roundToInt

/** One bounded, transient frame for a viewer opening; never an export or saved-state bitmap. */
internal data class MediaViewerFrame(val bitmap: Bitmap, val bounds: Rect) {
    companion object {
        fun capture(view: View): MediaViewerFrame? {
            val bounds=Rect()
            if(!view.getGlobalVisibleRect(bounds) || bounds.isEmpty || view.width<=0 || view.height<=0) return null
            val location=IntArray(2).also(view::getLocationOnScreen)
            return try {
                val bitmap=if(view is TextureView) {
                    if(!view.isAvailable) return null
                    val scale=minOf(1f,720f/maxOf(view.width,view.height))
                    val full=view.getBitmap((view.width*scale).roundToInt().coerceAtLeast(1),
                        (view.height*scale).roundToInt().coerceAtLeast(1)) ?: return null
                    val x=((bounds.left-location[0])*full.width.toFloat()/view.width).roundToInt().coerceIn(0,full.width-1)
                    val y=((bounds.top-location[1])*full.height.toFloat()/view.height).roundToInt().coerceIn(0,full.height-1)
                    val width=(bounds.width()*full.width.toFloat()/view.width).roundToInt().coerceIn(1,full.width-x)
                    val height=(bounds.height()*full.height.toFloat()/view.height).roundToInt().coerceIn(1,full.height-y)
                    Bitmap.createBitmap(full,x,y,width,height)
                } else {
                    val scale=minOf(1f,720f/maxOf(bounds.width(),bounds.height()))
                    Bitmap.createBitmap((bounds.width()*scale).roundToInt().coerceAtLeast(1),
                        (bounds.height()*scale).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also { bitmap ->
                        val canvas=Canvas(bitmap)
                        canvas.scale(bitmap.width.toFloat()/bounds.width(),bitmap.height.toFloat()/bounds.height())
                        canvas.translate((location[0]-bounds.left).toFloat(),(location[1]-bounds.top).toFloat())
                        view.draw(canvas)
                    }
                }
                MediaViewerFrame(bitmap,bounds)
            } catch(_: RuntimeException) { null }
            catch(_: OutOfMemoryError) { null } // A transition frame is optional under memory pressure.
        }
    }
}
