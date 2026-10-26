package one.njk.sao.usage

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw
import androidx.transition.ChangeBounds
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.motion.MotionUtils
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.transition.MaterialFadeThrough

/** Presentation only: no timers while idle, persisted state, or interpolated usage counts. */
internal class UsageMotion(private val root: ViewGroup): AutoCloseable {
    private data class Key(val group: Int,val metric: Int)
    private val values=mutableMapOf<Key,MaterialTextView>()
    private var previous=emptyMap<Key,String>()
    private val running=mutableSetOf<ValueChange>()
    private val retired=mutableSetOf<()->Unit>()
    private val duration=MotionUtils.resolveThemeDuration(root.context,com.google.android.material.R.attr.motionDurationMedium2,300).toLong()
    private val easing=MotionUtils.resolveThemeInterpolator(root.context,com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
        android.view.animation.AccelerateDecelerateInterpolator())

    fun beginUpdate() {
        stop()
        previous=values.filterValues { it.isAttachedToWindow && it.getGlobalVisibleRect(Rect()) }.mapValues { it.value.text.toString() }
        values.clear()
    }
    fun endUpdate() { previous=emptyMap() }
    fun value(group: Int,metric: Int,value: String,factory: (String)->MaterialTextView): View {
        val key=Key(group,metric)
        val target=factory(value)
        values[key]=target
        val before=previous[key]
        if(before==null || before==value || !ValueAnimator.areAnimatorsEnabled()) return target
        val frame=FrameLayout(root.context).apply { addView(target,FrameLayout.LayoutParams(-1,-2)) }
        val change=ValueChange(frame,target,before,factory)
        running.add(change)
        frame.doOnPreDraw { change.start() }
        return frame
    }

    fun calendar(column: LinearLayout,body: View,animate: Boolean,cleanup: ()->Unit) {
        finishCalendar()
        val old=column.getChildAt(1)
        if(animate && old!=null && root.isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            val bounds=ChangeBounds().apply { for(index in 0 until root.childCount) addTarget(root.getChildAt(index)) }
            val transition=TransitionSet().addTransition(bounds)
                .addTransition(MaterialFadeThrough().addTarget(old).addTarget(body))
                .setDuration(duration).setInterpolator(easing)
            retired.add(cleanup)
            transition.addListener(object: TransitionListenerAdapter() {
                override fun onTransitionEnd(transition: Transition) { retire(cleanup); transition.removeListener(this) }
            })
            TransitionManager.beginDelayedTransition(root,transition)
        } else cleanup()
        if(old!=null) column.removeView(old)
        column.addView(body,LinearLayout.LayoutParams(-1,-2).apply { topMargin=(8*root.resources.displayMetrics.density).toInt() })
    }
    fun stop() {
        finishCalendar()
        running.toList().forEach { it.finish() }
        previous=emptyMap()
    }
    private fun retire(cleanup: ()->Unit) { if(retired.remove(cleanup)) cleanup() }
    private fun finishCalendar() {
        TransitionManager.endTransitions(root)
        // A pending transition can be replaced before its first frame, without an end callback.
        retired.toList().forEach(::retire)
    }
    override fun close() { stop(); values.clear() }

    /** The old text is an overlay, so only the final value determines layout and accessibility. */
    private inner class ValueChange(val frame: FrameLayout,val target: MaterialTextView,
        val before: String,val factory: (String)->MaterialTextView) {
        private var animator: ValueAnimator?=null
        private var finished=false
        fun start() {
            if(finished) return
            if(!frame.isAttachedToWindow || !frame.getGlobalVisibleRect(Rect()) || !ValueAnimator.areAnimatorsEnabled()) { finish(); return }
            val old=factory(before).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                layoutDirection=target.layoutDirection; textDirection=target.textDirection; textAlignment=target.textAlignment
                measure(View.MeasureSpec.makeMeasureSpec(frame.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(frame.height,View.MeasureSpec.EXACTLY))
                layout(0,0,frame.width,frame.height)
            }
            frame.overlay.add(old)
            val distance=8*frame.resources.displayMetrics.density
            animator=ValueAnimator.ofFloat(0f,1f).apply {
                duration=this@UsageMotion.duration; interpolator=easing
                addUpdateListener {
                    val fraction=it.animatedValue as Float
                    old.alpha=1-fraction; old.translationY=-distance*fraction
                    target.alpha=fraction; target.translationY=distance*(1-fraction)
                }
                addListener(object: AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { finish() } })
                start()
            }
        }
        fun finish() {
            if(finished) return
            finished=true
            animator?.cancel(); animator=null
            frame.overlay.clear(); target.alpha=1f; target.translationY=0f
            running.remove(this)
        }
    }
}
