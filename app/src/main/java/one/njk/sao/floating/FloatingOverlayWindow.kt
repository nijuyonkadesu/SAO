package one.njk.sao.floating

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/** Absolute display coordinates; SAO applies the system-bar/cutout insets once. */
internal object FloatingOverlayWindow {
    @SuppressLint("RtlHardcoded") // Physical left/right screen edges, independent of layout direction.
    fun params(title: String, touchable: Boolean = true) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // SAO animates its own tuck. WM move animations can retain a previous rotation's frame.
        if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
        setTitle(title)
        // A decorative dismiss surface must allow touches through to the app below it.
        if (!touchable) alpha = .75f
    }
}
