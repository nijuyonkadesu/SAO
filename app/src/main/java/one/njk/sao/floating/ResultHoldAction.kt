package one.njk.sao.floating

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import kotlin.math.abs

/** Short tap copies; a stationary hold navigates once. Scrolling and recycling cancel the hold. */
internal class ResultHoldAction(private val view: View, private val enabled: () -> Boolean,
    private val delay: () -> Long, private val tap: () -> Unit, private val hold: () -> Unit) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val accessibility = view.context.getSystemService(AccessibilityManager::class.java)
    private var tracking = false
    private var consumed = false
    private var moved = false
    private var x = 0f
    private var y = 0f
    private val activate = Runnable {
        if (tracking && !moved && enabled() && view.isAttachedToWindow && view.isShown && view.hasWindowFocus()) {
            consumed = true; view.isPressed = false
            view.parent?.requestDisallowInterceptTouchEvent(true)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hold()
        }
    }
    init { install() }
    @SuppressLint("ClickableViewAccessibility")
    private fun install() {
        view.setOnLongClickListener { if (enabled()) { hold(); true } else false }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    if (!enabled() || accessibility?.isTouchExplorationEnabled == true) return@setOnTouchListener false
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
