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

/** Marquees stay animated. Use ActivityScenario's Main branch without waiting for global idleness. */
internal fun <A : Activity> ActivityScenario<A>.onMainActivity(action: (A) -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { onActivity { action(it) } }
}

/** ActivityScenario also waits for idle on launch/close. Visual tests explicitly resume marquee. */
class PickerMarqueeRule : TestRule {
    fun animate(activity: PickerActivity) = activity.dockMarquee(true)
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (activity is PickerActivity && stage == Stage.RESUMED) activity.dockMarquee(false)
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try { base.evaluate() }
            finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
        }
    }
}
