package one.njk.sao.floating

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import one.njk.sao.R

/** Marquees stay animated. Use ActivityScenario's Main branch without waiting for global idleness. */
internal fun <A : Activity> ActivityScenario<A>.onMainActivity(action: (A) -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { onActivity { action(it) } }
}

/** ActivityScenario also waits for idle on launch/close. Visual tests explicitly resume marquee. */
class PickerMarqueeRule : TestRule {
    private fun selected(activity: PickerActivity, active: Boolean) {
        for (id in listOf(R.id.selection_title, R.id.selection_status, R.id.capability_hint))
            activity.findViewById<android.widget.TextView>(id).isSelected = active
    }
    fun animate(activity: PickerActivity) = selected(activity, true)
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (activity is PickerActivity && stage == Stage.RESUMED) selected(activity, false)
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try { base.evaluate() }
            finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
        }
    }
}
