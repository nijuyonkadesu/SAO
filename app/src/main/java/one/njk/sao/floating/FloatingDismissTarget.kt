package one.njk.sao.floating

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Insets
import android.graphics.Rect
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import one.njk.sao.R
import kotlin.math.ceil

/** Small, non-focusable, non-touchable overlay, present only during an actual icon drag. */
internal class FloatingDismissTarget(context: Context, private val windows: WindowManager) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val target = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
        id = R.id.floating_dismiss_target
        text = context.getString(R.string.floating_drag_dismiss)
        icon = AppCompatResources.getDrawable(context, R.drawable.ic_picker_close)
        iconSize = dp(24); iconPadding = dp(8)
        cornerRadius = dp(32); minHeight = dp(56)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        insetTop = 0; insetBottom = 0
        setPadding(dp(20), dp(8), dp(20), dp(8))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val params = FloatingOverlayWindow.params("SAO dismiss target", touchable = false)
    private val hitArea = Rect()
    private var attached = false
    private var styled = false
    var hovered = false
        private set

    fun show(bounds: Rect, insets: Insets, imeBottom: Int) {
        if (attached) return
        setHovered(false)
        val textWidth = maxOf(target.paint.measureText(target.context.getString(R.string.floating_drag_dismiss)),
            target.paint.measureText(target.context.getString(R.string.floating_release_dismiss)))
        val width = minOf(maxOf(0, bounds.width() - insets.left - insets.right - dp(32)),
            ceil(textWidth).toInt() + target.paddingLeft + target.paddingRight + target.iconSize + target.iconPadding + dp(8))
        target.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        params.x = insets.left + (bounds.width() - insets.left - insets.right - target.measuredWidth) / 2
        params.y = maxOf(insets.top + dp(8), bounds.height() - maxOf(insets.bottom, imeBottom) - target.measuredHeight - dp(24))
        // Keep the target steady when its hover label changes.
        params.width = target.measuredWidth; params.height = target.measuredHeight
        hitArea.set(params.x, params.y, params.x + target.measuredWidth, params.y + target.measuredHeight)
        hitArea.inset(-dp(20), -dp(20))
        windows.addView(target, params); attached = true
    }

    fun contains(centerX: Int, centerY: Int) = attached && hitArea.contains(centerX, centerY)

    fun setHovered(value: Boolean): Boolean {
        if (styled && hovered == value) return false
        val changed = hovered != value
        styled = true
        hovered = value
        val color = MaterialColors.getColor(target, if (value) com.google.android.material.R.attr.colorErrorContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val foreground = MaterialColors.getColor(target, if (value) com.google.android.material.R.attr.colorOnErrorContainer
            else com.google.android.material.R.attr.colorOnSurface)
        target.backgroundTintList = ColorStateList.valueOf(color)
        target.setTextColor(foreground); target.iconTint = ColorStateList.valueOf(foreground)
        target.text = target.context.getString(if (value) R.string.floating_release_dismiss else R.string.floating_drag_dismiss)
        return changed
    }

    fun hide() {
        if (attached) { windows.removeView(target); attached = false }
        hovered = false; styled = false; hitArea.setEmpty()
    }
}
