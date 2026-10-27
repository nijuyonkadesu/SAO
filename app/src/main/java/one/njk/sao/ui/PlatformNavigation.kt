package one.njk.sao.ui

import android.animation.ValueAnimator
import android.content.Context
import androidx.core.content.res.use
import androidx.navigation.NavOptions

/** Navigation's equivalent of FragmentTransaction.TRANSIT_FRAGMENT_MATCH_ACTIVITY_OPEN/CLOSE.
 * Resolve the platform resources, without copying animations or prescribing their timing. */
internal fun platformNavigationOptions(context: Context): NavOptions {
    val attributes=intArrayOf(android.R.attr.activityOpenEnterAnimation,android.R.attr.activityOpenExitAnimation,
        android.R.attr.activityCloseEnterAnimation,android.R.attr.activityCloseExitAnimation)
    return context.obtainStyledAttributes(android.R.style.Animation_Activity,attributes).use { values ->
        fun resource(index: Int)=if(ValueAnimator.areAnimatorsEnabled()) values.getResourceId(index,0) else 0
        NavOptions.Builder().setEnterAnim(resource(0)).setExitAnim(resource(1))
            .setPopEnterAnim(resource(2)).setPopExitAnim(resource(3)).build()
    }
}
