package one.njk.sao.ui

import android.animation.ValueAnimator
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.motion.MotionUtils
import com.google.android.material.transition.MaterialSharedAxis

/** Short, lifecycle-owned transitions for explicit navigation, never media binding or idle work. */
internal class SaoMotion(private val owner: LifecycleOwner): DefaultLifecycleObserver,AutoCloseable {
    private val active=mutableSetOf<View>()
    private var closed=false
    init { owner.lifecycle.addObserver(this) }

    fun visible(view: View,show: Boolean,animate: Boolean=true) {
        if(view.isVisible==show) { if(!show) finish(view); return }
        finish(view)
        view.isVisible=show
        if(show && animate) reveal(view)
    }
    fun reveal(view: View) {
        finish(view)
        if(closed || !ValueAnimator.areAnimatorsEnabled() || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !view.isShown) return
        active.add(view)
        view.alpha=0f; view.translationY=4*view.resources.displayMetrics.density
        val duration=MotionUtils.resolveThemeDuration(view.context,com.google.android.material.R.attr.motionDurationShort3,150).toLong()
        val easing=MotionUtils.resolveThemeInterpolator(view.context,com.google.android.material.R.attr.motionEasingEmphasizedDecelerateInterpolator,
            android.view.animation.DecelerateInterpolator())
        view.animate().alpha(1f).translationY(0f).setDuration(duration).setInterpolator(easing).withEndAction {
            active.remove(view); view.alpha=1f; view.translationY=0f
        }.start()
    }
    fun enter(view: View,restored: Boolean) {
        if(!restored) view.doOnPreDraw { if(!closed) reveal(view) }
    }
    private fun finish(view: View) {
        if(active.remove(view)) { view.animate().cancel(); view.alpha=1f; view.translationY=0f }
    }
    override fun onStop(owner: LifecycleOwner) { active.toList().forEach(::finish) }
    override fun onDestroy(owner: LifecycleOwner) { close() }
    override fun close() {
        closed=true; active.toList().forEach(::finish); owner.lifecycle.removeObserver(this)
    }
    companion object {
        fun navigation(fragment: Fragment) {
            if(!ValueAnimator.areAnimatorsEnabled()) return
            fragment.enterTransition=MaterialSharedAxis(MaterialSharedAxis.X,true)
            fragment.exitTransition=MaterialSharedAxis(MaterialSharedAxis.X,true)
            fragment.returnTransition=MaterialSharedAxis(MaterialSharedAxis.X,false)
            fragment.reenterTransition=MaterialSharedAxis(MaterialSharedAxis.X,false)
        }
    }
}
