package one.njk.sao.floating

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import com.google.android.material.carousel.MaskableFrameLayout
import kotlin.math.abs

/** Short tap copies; a stationary hold navigates once. Scrolling and recycling cancel the hold. */
internal class ResultHoldAction(private val view: View, private val enabled: () -> Boolean,
    private val delay: () -> Long, private val tap: () -> Unit, private val hold: () -> Unit,
    private val ignoreHold: () -> Boolean = { false }) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val accessibility = view.context.getSystemService(AccessibilityManager::class.java)
    private var tracking = false
    private var consumed = false
    private var moved = false
    private var x = 0f
    private var y = 0f
    private val activate = Runnable {
        if (tracking && !moved && (enabled() || ignoreHold()) && view.isAttachedToWindow && view.isShown && view.hasWindowFocus()) {
            consumed = true; view.isPressed = false
            view.parent?.requestDisallowInterceptTouchEvent(true)
            if (!ignoreHold()) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                hold()
            }
        }
    }
    init { install() }
    @SuppressLint("ClickableViewAccessibility")
    private fun install() {
        view.setOnLongClickListener { if (ignoreHold()) true else if (enabled()) { hold(); true } else false }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    // OnTouchListener runs before MaskableFrameLayout.onTouchEvent. Respect its
                    // visible mask here too, or a clipped neighbor can steal this card's press.
                    val mask = (view as? MaskableFrameLayout)?.maskRectF
                    if (mask != null && !mask.isEmpty && !mask.contains(event.x, event.y))
                        return@setOnTouchListener false
                    if ((!enabled() && !ignoreHold()) || accessibility?.isTouchExplorationEnabled == true) return@setOnTouchListener false
                    tracking = true; consumed = false; moved = false
                    x = event.x; y = event.y; view.isPressed = true
                    view.postDelayed(activate, delay()); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    if (abs(event.x - x) > slop || abs(event.y - y) > slop || event.pointerCount != 1) {
                        moved = true; view.removeCallbacks(activate); view.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tracking) return@setOnTouchListener false
                    val click = !consumed && !moved
                    cancel()
                    if (click) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    val handled = tracking; cancel(); handled
                }
                else -> tracking
            }
        }
        view.setOnClickListener { tap() }
    }
    fun cancel() {
        view.removeCallbacks(activate); tracking = false; consumed = false; moved = false
        view.isPressed = false; view.parent?.requestDisallowInterceptTouchEvent(false)
    }
}
