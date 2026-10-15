package one.njk.sao.floating

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import one.njk.sao.R
import one.njk.sao.databinding.ViewHoldDragChoicesBinding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Gesture-only floating surface in the existing window; it never steals IME focus. */
internal class HoldDragSelector<T>(private val anchor: View, private val host: FrameLayout,
    private val choices: () -> List<T>, private val selected: () -> T,
    private val label: (T) -> String, private val identity: () -> Any?, private val apply: (T) -> Unit,
    private val title: Int = R.string.search_type,
    private val chooseAction: Int = R.string.search_hold_accessible_choose,
    private val nextAction: Int = R.string.search_hold_next,
    private val previousAction: Int = R.string.search_hold_previous,
    private val opening: () -> Unit = {},
    private val hint: (T) -> String = { anchor.context.getString(R.string.search_hold_release) },
    private val holdDelay: () -> Long = { HOLD_MS }) {
    private val density = anchor.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(anchor.context).scaledTouchSlop
    private val accessibility = anchor.context.getSystemService(AccessibilityManager::class.java)
    private var tracking = false
    private var consumeRelease = false
    private var downX = 0f
    private var downY = 0f
    private var pointer = -1
    private var key: Any? = null
    private var values = emptyList<T>()
    private var cursor: LoopingChoiceCursor? = null
    private var surface: ViewHoldDragChoicesBinding? = null
    private var rows = emptyList<TextView>()
    private var visibleRows = 3
    private var left = 0
    private var top = 0
    private var width = 0
    private var inLane = true
    private var entering = false
    private val arm = Runnable { activate() }
    val visible get() = surface != null
    private val layoutChanged = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (tracking && (l != oldL || t != oldT || r != oldR || b != oldB)) cancel()
    }

    init {
        installTouch()
        anchor.addOnLayoutChangeListener(layoutChanged)
        // Accessibility users retain the full selector and explicit next/previous actions.
        anchor.setOnLongClickListener { anchor.performClick(); true }
        ViewCompat.replaceAccessibilityAction(anchor, AccessibilityActionCompat.ACTION_LONG_CLICK,
            anchor.context.getString(chooseAction)) { _, _ -> anchor.performClick(); true }
        ViewCompat.replaceAccessibilityAction(anchor, AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
            anchor.context.getString(nextAction)) { _, _ -> cycle(1) }
        ViewCompat.replaceAccessibilityAction(anchor, AccessibilityActionCompat.ACTION_SCROLL_BACKWARD,
            anchor.context.getString(previousAction)) { _, _ -> cycle(-1) }
    }

    @SuppressLint("ClickableViewAccessibility") // Short taps call performClick; accessible actions are installed above.
    private fun installTouch() {
        anchor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel(); consumeRelease = false
                    if (accessibility?.isTouchExplorationEnabled == true) return@setOnTouchListener false
                    values = choices()
                    if (values.isEmpty()) return@setOnTouchListener false
                    tracking = true; key = identity(); pointer = event.getPointerId(0)
                    downX = event.rawX; downY = event.rawY
                    anchor.isPressed = true
                    anchor.postDelayed(arm, holdDelay())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener consumeRelease
                    if (event.pointerCount != 1 || event.getPointerId(0) != pointer) { cancel(); return@setOnTouchListener true }
                    if (!visible) {
                        if (max(abs(event.rawX - downX), abs(event.rawY - downY)) > slop) {
                            anchor.removeCallbacks(arm); tracking = false
                            consumeRelease = true; cancelDefaultTouch()
                        }
                        true // The parent can still intercept horizontal swipes before activation.
                    } else {
                        preview(event.rawX, event.rawY); true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val held = visible
                    if (held) preview(event.rawX, event.rawY)
                    val choice = if (held && inLane && key == identity() && values == choices()) cursor?.let { values[it.index] } else null
                    val tapped = tracking && !held && key == identity() && values == choices() && anchor.isEnabled && anchor.isShown
                    val consumed = held || tracking || consumeRelease
                    clear(resetPress = consumed); consumeRelease = false
                    choice?.let(apply)
                    if (tapped) anchor.performClick()
                    consumed
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    val consumed = visible || consumeRelease
                    cancel(); consumed
                }
                else -> visible || consumeRelease
            }
        }
    }

    @SuppressLint("RtlHardcoded") // Placement uses physical screen coordinates, including in RTL layouts.
    private fun activate() {
        if (!tracking) return
        if (!anchor.isShown || !anchor.isAttachedToWindow || key != identity() || values != choices()) { cancel(); return }
        opening()
        val frame = Rect().also(host::getWindowVisibleDisplayFrame)
        val rootPosition = IntArray(2).also(host::getLocationOnScreen)
        frame.offset(-rootPosition[0], -rootPosition[1])
        val margin = px(8)
        frame.intersect(0, 0, host.width, host.height)
        if (frame.width() < px(120) || frame.height() < px(48) + margin * 2) { cancel(); return }
        val view = ViewHoldDragChoicesBinding.inflate(LayoutInflater.from(anchor.context))
        view.holdChoiceTitle.setText(title)
        val prototype = TextView(anchor.context).apply { TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge) }
        var rowHeight = max(px(48), prototype.lineHeight * 2 + px(12))
        width = px(248).coerceAtMost(frame.width() - margin * 2)
        val available = frame.height() - margin * 2
        visibleRows = if (available >= rowHeight * 3 + px(80)) 3 else 1
        if (visibleRows == 1) {
            view.holdChoiceTitle.isVisible = false; view.holdChoiceHint.isVisible = false
            rowHeight = rowHeight.coerceAtMost(available - px(16))
        }
        view.holdChoiceViewport.layoutParams = view.holdChoiceViewport.layoutParams.apply { height = rowHeight * visibleRows }
        view.holdChoiceHighlight.layoutParams = view.holdChoiceHighlight.layoutParams.apply { height = rowHeight }
        view.holdChoiceRows.layoutParams = view.holdChoiceRows.layoutParams.apply { height = rowHeight * (visibleRows + 2) }
        val onSurface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val onSelected = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnPrimaryContainer)
        rows = List(visibleRows + 2) { index ->
            TextView(anchor.context).apply {
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPaddingRelative(px(44), px(2), px(12), px(2))
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                val focal = index == (visibleRows + 2) / 2
                setTextColor(if (focal) onSelected else onSurface)
                alpha = if (focal) 1f else .65f
                setTypeface(null, if (focal) Typeface.BOLD else Typeface.NORMAL)
                view.holdChoiceRows.addView(this, LinearLayout.LayoutParams(-1, rowHeight))
            }
        }
        cursor = LoopingChoiceCursor(values.size, values.indexOf(selected()).coerceAtLeast(0), rowHeight.toFloat())
        inLane = true
        surface = view
        bindRows()
        view.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        val anchorPosition = IntArray(2).also(anchor::getLocationOnScreen)
        left = (anchorPosition[0] - rootPosition[0] - px(8)).coerceIn(frame.left + margin, frame.right - margin - width)
        val viewportTop = px(8) + if (visibleRows == 3) view.holdChoiceTitle.measuredHeight else 0
        val focalTop = viewportTop + rowHeight * (visibleRows / 2)
        top = (anchorPosition[1] - rootPosition[1] + anchor.height / 2 - focalTop - rowHeight / 2)
            .coerceIn(frame.top + margin, (frame.bottom - margin - view.root.measuredHeight).coerceAtLeast(frame.top + margin))
        host.addView(view.root, FrameLayout.LayoutParams(width, view.root.measuredHeight).apply {
            gravity = Gravity.TOP or Gravity.LEFT; leftMargin = left; topMargin = top
        })
        view.root.pivotX = (anchorPosition[0] - rootPosition[0] + anchor.width / 2 - left).toFloat()
        view.root.pivotY = (anchorPosition[1] - rootPosition[1] + anchor.height / 2 - top).toFloat()
        entering = true
        view.root.alpha = 0f; view.root.scaleX = .92f; view.root.scaleY = .8f
        view.root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(100).start()
        anchor.parent.requestDisallowInterceptTouchEvent(true)
        // Stop View's later native long-click and click; the source view keeps the pointer stream.
        cancelDefaultTouch()
        anchor.isActivated = true
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun preview(x: Float, y: Float) {
        val view = surface ?: return
        if (entering) {
            entering = false; view.root.animate().cancel()
            view.root.scaleX = 1f; view.root.scaleY = 1f
        }
        val rootPosition = IntArray(2).also(host::getLocationOnScreen)
        val nextLane = x - rootPosition[0] in (left - px(32)).toFloat()..(left + width + px(32)).toFloat()
        if (nextLane != inLane) {
            view.holdChoiceHint.text = if (nextLane) cursor?.let { hint(values[it.index]) } else anchor.context.getString(R.string.search_hold_cancel)
        }
        inLane = nextLane
        view.root.alpha = if (inLane) 1f else .45f
        val selection = cursor ?: return
        if (selection.move(downY - y)) {
            bindRows()
            if (inLane) anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        view.holdChoiceRows.translationY = -selection.step + selection.translation
    }

    private fun bindRows() {
        val selection = cursor ?: return
        val center = rows.size / 2
        rows.forEachIndexed { index, row ->
            row.text = label(values[selection.neighbor(index - center)])
        }
        surface?.holdChoiceRows?.translationY = -selection.step + selection.translation
        surface?.root?.contentDescription = label(values[selection.index])
        if (inLane) surface?.holdChoiceHint?.text = hint(values[selection.index])
    }

    /** Source, capabilities or navigation changes invalidate the provisional choice. */
    fun update() { if (tracking && (key != identity() || values != choices())) cancel() }
    fun cancel(): Boolean {
        val wasActive = tracking || visible
        if (wasActive) { consumeRelease = true; cancelDefaultTouch() }
        clear()
        return wasActive
    }
    private fun clear(resetPress: Boolean = true) {
        anchor.removeCallbacks(arm); tracking = false
        surface?.root?.let { it.animate().cancel(); host.removeView(it) }
        surface = null; cursor = null; rows = emptyList(); entering = false
        anchor.isActivated = false
        if (resetPress) anchor.isPressed = false
        anchor.parent?.requestDisallowInterceptTouchEvent(false)
    }
    private fun cancelDefaultTouch() {
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0).let {
            anchor.onTouchEvent(it); it.recycle()
        }
    }
    private fun cycle(direction: Int): Boolean {
        val items = choices()
        if (items.isEmpty()) return false
        cancel()
        val current = items.indexOf(selected())
        val index = if (current < 0) { if (direction > 0) 0 else items.lastIndex }
            else Math.floorMod(current + direction, items.size)
        apply(items[index]); return true
    }
    fun dispose() {
        cancel(); anchor.setOnTouchListener(null); anchor.setOnLongClickListener(null)
        anchor.removeOnLayoutChangeListener(layoutChanged)
        listOf(AccessibilityActionCompat.ACTION_LONG_CLICK, AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
            AccessibilityActionCompat.ACTION_SCROLL_BACKWARD).forEach { ViewCompat.removeAccessibilityAction(anchor, it.id) }
    }
    private fun px(dp: Int) = (dp * density).roundToInt()
    companion object { val HOLD_MS = FloatingOptions.DEFAULT_LONG_PRESS_MS.toLong() }
}
