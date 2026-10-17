package one.njk.sao.floating

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import one.njk.sao.R
import one.njk.sao.databinding.ItemFloatingMediaBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.roundToInt

class PickerSelectionDeviceTest {
    @Test fun selectionStrokeTracksAllFourVisibleEdgesAtFullAndPeekWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val configuration = Configuration(app.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val context = DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(
                    app.createConfigurationContext(configuration), R.style.Theme_SAO))
                val root = ItemFloatingMediaBinding.inflate(LayoutInflater.from(context)).root
                val density = context.resources.displayMetrics.density
                val width = (260 * density).roundToInt(); val height = (220 * density).roundToInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val color = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    for (insetDp in listOf(0, 24, 64, 100)) {
                        val inset = insetDp * density
                        val mask = RectF(inset, 0f, width - inset, height.toFloat())
                        root.setMaskRectF(mask)
                        root.isSelected = true
                        bitmap.eraseColor(0); root.draw(Canvas(bitmap))
                        val halfStroke = density
                        val points = listOf(
                            (mask.left + halfStroke).roundToInt() to height / 2,
                            (mask.right - halfStroke).roundToInt() to height / 2,
                            width / 2 to halfStroke.roundToInt(),
                            width / 2 to (height - halfStroke).roundToInt(),
                        )
                        points.forEach { (x, y) -> assertEquals("A $insetDp dp mask must retain every selection edge in mode $night", color, bitmap.getPixel(x, y)) }
                        root.isSelected = false
                        bitmap.eraseColor(0); root.draw(Canvas(bitmap))
                        points.forEach { (x, y) -> assertNotEquals("Recycled unselected cards must not retain the border", color, bitmap.getPixel(x, y)) }
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
}
