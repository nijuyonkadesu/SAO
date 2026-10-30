package one.njk.sao.floating

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/** Final IME geometry is measured once; frames change card bounds without laying out its children. */
internal class PickerWindowMotion(owner: LifecycleOwner,private val root: View,private val card: MaterialCardView,
    private val settled: ()->Unit): DefaultLifecycleObserver,AutoCloseable {
    private val lifecycle=owner.lifecycle
    private var animation: WindowInsetsAnimationCompat?=null
    private var started=false
    private var fraction=0f
    private val start=Rect()
    private val end=Rect()
    private var exit: Rect?=null
    val animating get()=animation!=null
    private val layout=View.OnLayoutChangeListener { _,l,t,r,b,_,_,_,_ ->
        val frozen=exit
        if(frozen!=null) bounds(frozen.left,frozen.top,frozen.right,frozen.bottom)
        else if(started) { end.set(l,t,r,b); draw(fraction) }
    }
    private val callback=object: WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(value: WindowInsetsAnimationCompat) {
            if(exit!=null || value.typeMask and WindowInsetsCompat.Type.ime()==0) return
            val visible=Rect(card.left,card.top,card.right,card.bottom)
            finish(); start.set(visible); animation=value
        }
        override fun onStart(value: WindowInsetsAnimationCompat,bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
            if(animation===value && ValueAnimator.areAnimatorsEnabled() && card.isLaidOut) {
                end.set(card.left,card.top,card.right,card.bottom); started=true; draw(0f)
            }
            return bounds
        }
        override fun onProgress(insets: WindowInsetsCompat,runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
            val current=animation
            if(started && current!=null && current in runningAnimations) draw(current.interpolatedFraction)
            return insets
        }
        override fun onEnd(value: WindowInsetsAnimationCompat) {
            if(animation===value) { finish(); settled() }
        }
    }
    init {
        lifecycle.addObserver(this); card.addOnLayoutChangeListener(layout)
        ViewCompat.setWindowInsetsAnimationCallback(root,callback)
    }
    private fun draw(value: Float) {
        fraction=value.coerceIn(0f,1f)
        fun between(a: Int,b: Int)=(a+(b-a)*fraction).roundToInt()
        bounds(between(start.left,end.left),between(start.top,end.top),between(start.right,end.right),between(start.bottom,end.bottom))
    }
    private fun bounds(left: Int,top: Int,right: Int,bottom: Int) {
        // View's bounds setters update drawing/hit regions without a measure/layout traversal.
        card.left=left; card.top=top; card.right=right; card.bottom=bottom; card.invalidateOutline()
    }
    fun finish() {
        animation=null
        if(started) { started=false; bounds(end.left,end.top,end.right,end.bottom) }
    }
    /** Window exit owns the final drawing; a late keyboard-hide layout must not move it again. */
    fun freezeForExit() {
        exit=Rect(card.left,card.top,card.right,card.bottom)
        animation=null; started=false
    }
    override fun onStop(owner: LifecycleOwner) { finish() }
    override fun onDestroy(owner: LifecycleOwner) { close() }
    override fun close() {
        finish(); card.removeOnLayoutChangeListener(layout)
        ViewCompat.setWindowInsetsAnimationCallback(root,null); lifecycle.removeObserver(this)
    }
}
