package one.njk.sao.floating

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import kotlin.math.roundToInt

/** Selection follows Material's current visible mask, including partially visible cards. */
class PickerMediaFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : MaskableFrameLayout(context, attrs, defStyleAttr) {
    private val selectionOutline = MaterialShapeDrawable(shapeAppearanceModel).apply {
        fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
        setStroke(2 * resources.displayMetrics.density,
            MaterialColors.getColor(this@PickerMediaFrame, androidx.appcompat.R.attr.colorPrimary))
    }

    init {
        setOnMaskChangedListener { mask ->
            selectionOutline.setBounds(mask.left.roundToInt(), mask.top.roundToInt(),
                mask.right.roundToInt(), mask.bottom.roundToInt())
            if (isSelected) invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // MaterialShapeDrawable insets its stroke into these bounds. Drawing after the
        // masked children avoids cutting a full-card outline off at the carousel's mask.
        if (isSelected && !maskRectF.isEmpty) selectionOutline.draw(canvas)
    }

    override fun setSelected(selected: Boolean) {
        val changed = isSelected != selected
        super.setSelected(selected)
        if (changed) invalidate()
    }
}
