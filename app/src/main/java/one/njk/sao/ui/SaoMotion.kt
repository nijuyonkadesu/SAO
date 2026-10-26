package one.njk.sao.ui

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.LayoutTransition
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.motion.MotionUtils
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.transition.SlideDistanceProvider
import com.google.android.material.color.MaterialColors
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager

/** Short, lifecycle-owned transitions for explicit navigation, never media binding or idle work. */
internal class SaoMotion(private val owner: LifecycleOwner): DefaultLifecycleObserver,AutoCloseable {
    private data class VisibilityRoot(val previous: LayoutTransition?,val transition: LayoutTransition)
    private val visibility=mutableMapOf<ViewGroup,VisibilityRoot>()
    private val fading=mutableSetOf<Animator>()
    private val roots=mutableSetOf<ViewGroup>()
    private val pending=mutableSetOf<ViewGroup>()
    private var closed=false
    init { owner.lifecycle.addObserver(this) }

    fun visible(view: View,show: Boolean,animate: Boolean=true) {
        if(view.isVisible==show) return
        if(animate && canAnimate(view)) (view.parent as? ViewGroup)?.let(::visibilityRoot)
        view.isVisible=show
    }
    private fun canAnimate(view: View)=!closed && ValueAnimator.areAnimatorsEnabled() &&
        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && view.isAttachedToWindow

    private fun visibilityRoot(root: ViewGroup) {
        if(root in visibility) return
        val transition=LayoutTransition().apply {
            // Changing-layout animations suppress parent layout and delay incoming controls.
            // Only native visibility transitions retain the outgoing drawing while GONE takes effect.
            disableTransitionType(LayoutTransition.CHANGE_APPEARING)
            disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
            disableTransitionType(LayoutTransition.CHANGING)
            setAnimateParentHierarchy(false)
            for(type in listOf(LayoutTransition.APPEARING,LayoutTransition.DISAPPEARING)) {
                val appearing=type==LayoutTransition.APPEARING
                setStartDelay(type,0); setDuration(type,if(appearing) 120L else 100L)
                setInterpolator(type,MotionUtils.resolveThemeInterpolator(root.context,
                    if(appearing) com.google.android.material.R.attr.motionEasingEmphasizedDecelerateInterpolator else com.google.android.material.R.attr.motionEasingEmphasizedAccelerateInterpolator,
                    android.view.animation.AccelerateDecelerateInterpolator()))
                setAnimator(type,ObjectAnimator.ofFloat(null,"alpha",if(appearing) 0f else 1f,if(appearing) 1f else 0f).apply {
                    addListener(object: AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) { fading.add(animation) }
                        override fun onAnimationEnd(animation: Animator) { fading.remove(animation); ((animation as? ObjectAnimator)?.target as? View)?.alpha=1f }
                    })
                })
            }
        }
        visibility[root]=VisibilityRoot(root.layoutTransition,transition)
        root.layoutTransition=transition
    }
    /** Bounds animate only for a small control row, never the media carousel or keyboard viewport. */
    fun layout(root: ViewGroup) {
        if(!canAnimate(root) || !root.isLaidOut || !root.isShown || !pending.add(root)) return
        TransitionManager.endTransitions(root)
        val movement=ChangeBounds().apply { resizeClip=true; duration=120 }
        for(index in 0 until root.childCount) movement.addTarget(root.getChildAt(index))
        roots.add(root)
        TransitionManager.beginDelayedTransition(root,movement)
        root.doOnPreDraw { pending.remove(root) }
    }
    private fun stop() {
        roots.forEach(TransitionManager::endTransitions); roots.clear(); pending.clear()
        fading.toList().forEach { it.end() }; fading.clear()
        visibility.forEach { (root,owned) -> if(root.layoutTransition===owned.transition) root.layoutTransition=owned.previous }
        visibility.clear()
    }
    override fun onStop(owner: LifecycleOwner) { stop() }
    override fun onDestroy(owner: LifecycleOwner) { close() }
    override fun close() {
        closed=true; stop(); owner.lifecycle.removeObserver(this)
    }
    companion object {
        fun surface(view: View) {
            (view as? ViewGroup)?.isTransitionGroup=true
            view.setBackgroundColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface))
        }
        private fun axis(forward: Boolean,distance: Int)=MaterialSharedAxis(MaterialSharedAxis.X,forward).apply {
            duration=140
            (primaryAnimatorProvider as SlideDistanceProvider).slideDistance=distance
            secondaryAnimatorProvider=null
        }
        fun navigation(fragment: Fragment) {
            if(!ValueAnimator.areAnimatorsEnabled()) return
            val distance=(12*fragment.resources.displayMetrics.density).toInt()
            fragment.enterTransition=axis(true,distance)
            fragment.exitTransition=axis(true,distance)
            fragment.returnTransition=axis(false,distance)
            fragment.reenterTransition=axis(false,distance)
        }
    }
}
