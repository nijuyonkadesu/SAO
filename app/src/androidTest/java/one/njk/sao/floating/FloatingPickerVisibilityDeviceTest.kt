package one.njk.sao.floating

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FloatingPickerVisibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val preferences = FloatingPreferences(app)
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun icon() = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull()
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000; var ready = false
        while (!ready && SystemClock.elapsedRealtime() < end) {
            instrumentation.runOnMainSync { ready = condition() }; SystemClock.sleep(20)
        }
        assertTrue(message, ready)
    }
    @Test fun closeReleasesTheIconBeforeActivityStopAndRecreationKeepsItHidden() = runBlocking {
        val old = preferences.current(); val source = access.sources().current()
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        preferences.autoHide(false); access.sources().select(null)
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { settings ->
                settings.onActivity { it.findViewById<View>(R.id.start_stop).performClick() }
                await("Icon should appear") { icon() != null }
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { picker ->
                    await("Picker should hide the icon") { FloatingSession.pickerVisible && icon() == null }
                    picker.recreate()
                    await("Recreated picker should keep the icon hidden") { FloatingSession.pickerVisible && icon() == null }
                    val drawn = CountDownLatch(1)
                    picker.onActivity { activity ->
                        val started = SystemClock.elapsedRealtimeNanos()
                        activity.findViewById<View>(R.id.close).performClick()
                        assertTrue(activity.isFinishing)
                        // Still in the click callback: neither onStop nor a system broadcast can
                        // have run. The icon must already be registered for its next render frame.
                        assertFalse("Dismissal must release picker ownership immediately", FloatingSession.pickerVisible)
                        val bubble = requireNotNull(icon()) { "The closing picker must restore its icon before onStop" }
                        bubble.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                bubble.viewTreeObserver.removeOnPreDrawListener(this)
                                android.util.Log.i("SAOPickerCloseTest", "dismiss_to_first_frame_ms=${(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0}")
                                drawn.countDown(); return true
                            }
                        })
                    }
                    assertTrue("Restored icon should render", drawn.await(5, TimeUnit.SECONDS))
                }
                await("Icon should remain after old picker cleanup") { !FloatingSession.pickerVisible && icon() != null }
            }
        } finally {
            app.stopService(Intent(app, FloatingService::class.java))
            preferences.autoHide(old.autoHide); access.sources().restore(source)
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
