package one.njk.sao.floating

import android.app.AppOpsManager
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.runBlocking
import one.njk.sao.MainActivity
import one.njk.sao.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real launcher/service/overlay behavior; never opens the media picker or sends messages. */
class FloatingLaunchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val preferences = FloatingPreferences(app)
    private lateinit var previous: FloatingOptions
    private var previousOverlay = AppOpsManager.MODE_DEFAULT

    private fun shell(value: String) = instrumentation.uiAutomation.executeShellCommand(value).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
    }
    private fun overlay(mode: String) { shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW $mode") }
    private fun icon() = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull()
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { ready = condition() }
            SystemClock.sleep(30)
        }
        assertTrue(label, ready)
    }
    private fun assertStopped() {
        SystemClock.sleep(400)
        instrumentation.runOnMainSync { assertFalse(FloatingSession.active.value); assertNull(icon()) }
    }
    private fun stop() {
        app.stopService(Intent(app, FloatingService::class.java))
        await("Stopping must remove the real overlay") { !FloatingSession.active.value && icon() == null }
    }
    @Before fun setup() = runBlocking {
        previous = preferences.current()
        previousOverlay = app.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.applicationInfo.uid, app.packageName)
        stop(); overlay("allow")
        preferences.autoHide(false); preferences.showOnLaunch(true)
    }
    @After fun cleanup() = runBlocking {
        stop()
        preferences.showOnLaunch(previous.showOnLaunch); preferences.autoHide(previous.autoHide)
        overlay(when (previousOverlay) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            else -> "default"
        })
    }

    @Test fun openingSaoShowsOneIconAndReusesItAcrossRecreationAndReentry() {
        val stored = java.io.File(app.filesDir, "datastore/floating_picker.preferences_pb")
        val before = stored.readBytes()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("Opening SAO must show the icon without pressing Show icon") { FloatingSession.active.value && icon()?.isAttachedToWindow == true }
            var original: FloatingActionButton? = null
            instrumentation.runOnMainSync { original = icon() }
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            await("The running overlay must be reused") { icon() === original && FloatingSession.active.value }
        }
        assertArrayEquals("Automatic startup must not write browsing/configuration preferences", before, stored.readBytes())
    }

    @Test fun dismissalStaysStoppedThroughRecreationUntilTheAppIsOpenedAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("Opening SAO must start the overlay") { icon() != null && FloatingSession.active.value }
            instrumentation.runOnMainSync { assertTrue(icon()!!.performLongClick()) }
            await("A user dismissal must stop the service") { !FloatingSession.active.value && icon() == null }
            assertStopped()
            scenario.recreate(); assertStopped()
            scenario.recreate(); assertStopped()
            scenario.moveToState(Lifecycle.State.CREATED); assertStopped()
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("Reopening SAO must restore the dismissed icon") { icon() != null && FloatingSession.active.value }
        }
    }

    @Test fun missingOverlayAccessDoesNotLaunchPermissionScreensAndGrantingItAllowsStartup() {
        overlay("ignore")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertStopped()
            scenario.onActivity { assertTrue(it.hasWindowFocus()) }
            scenario.moveToState(Lifecycle.State.CREATED)
            overlay("allow")
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("Returning after granting overlay access must show the icon") { FloatingSession.active.value && icon() != null }
        }
    }

    @Test fun settingsOptOutSurvivesRecreationAndStillAllowsAnExplicitShow() = runBlocking {
        // This case exercises the existing explicit Show button, including its notification prerequisite.
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        preferences.showOnLaunch(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertStopped()
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                host.navController.navigate(R.id.settingsFragment)
            }
            onView(withId(R.id.start_stop)).perform(scrollTo(), click())
            await("Manual Show icon must still work when automatic startup is disabled") { FloatingSession.active.value && icon() != null }
            onView(withId(R.id.stop_floating)).perform(click())
            await("The inline Stop control must stop immediately") { !FloatingSession.active.value && icon() == null }
            onView(withId(R.id.show_on_launch)).perform(scrollTo())
            scenario.onActivity { assertFalse(it.findViewById<Chip>(R.id.show_on_launch).isChecked) }
            scenario.recreate(); assertStopped()
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            assertStopped()
            onView(withId(R.id.show_on_launch)).perform(scrollTo(), click())
            await("The inline startup choice must persist") { runBlocking { preferences.current().showOnLaunch } }
            assertStopped()
            scenario.recreate()
            scenario.onActivity { assertTrue(it.findViewById<Chip>(R.id.show_on_launch).isChecked) }
            assertStopped()
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            await("The enabled startup setting must take effect on reentry") { FloatingSession.active.value && icon() != null }
        }
    }
}
