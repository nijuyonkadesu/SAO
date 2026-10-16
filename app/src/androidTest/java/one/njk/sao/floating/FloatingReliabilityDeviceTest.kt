package one.njk.sao.floating

import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import kotlinx.coroutines.runBlocking
import one.njk.sao.MainActivity
import one.njk.sao.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FloatingReliabilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val preferences = FloatingPreferences(app)
    private var previousLaunch = true
    private var previousExempt = false
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
    }
    private fun exempt(value: Boolean) { shell("cmd deviceidle whitelist ${if (value) "+" else "-"}${app.packageName}") }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue(label, condition())
    }
    private fun openSettings(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment)
                .navController.navigate(R.id.settingsFragment)
        }
    }
    private fun awaitBattery(scenario: ActivityScenario<MainActivity>, value: Int) = await("Battery status must reflect system settings") {
        var ready = false
        scenario.onActivity { activity -> ready = activity.findViewById<Chip>(R.id.battery_optimization)?.text == activity.getString(value) }
        ready
    }
    private fun awaitFocus(scenario: ActivityScenario<MainActivity>, expected: Boolean) = await("System settings must ${if (expected) "return to" else "open outside"} SAO") {
        var focused = false
        scenario.onActivity { focused = it.hasWindowFocus() }
        focused == expected
    }
    private fun screenshot(name: String) {
        if (!InstrumentationRegistry.getArguments().containsKey("reliabilityShots")) return
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(app.filesDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Before fun setup() = runBlocking {
        previousLaunch = preferences.current().showOnLaunch
        previousExempt = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        preferences.showOnLaunch(false)
    }
    @After fun cleanup() = runBlocking {
        exempt(previousExempt); preferences.showOnLaunch(previousLaunch)
    }

    @Test fun batteryStatusRefreshesOnReturnAndButtonsOpenTheCorrectSystemScreens() {
        exempt(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettings(scenario); awaitBattery(scenario, R.string.floating_battery_optimized)
            val battery = instrumentation.addMonitor(IntentFilter(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), null, false)
            val appDetails = instrumentation.addMonitor(IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addDataScheme("package"); addDataSchemeSpecificPart(app.packageName, android.os.PatternMatcher.PATTERN_LITERAL)
            }, null, false)
            try {
                onView(withId(R.id.battery_optimization)).perform(scrollTo(), click())
                awaitFocus(scenario, false)
                assertEquals("Battery action must use the supported public settings screen", 1, battery.hits)
                exempt(true); shell("input keyevent KEYCODE_BACK")
                awaitFocus(scenario, true); awaitBattery(scenario, R.string.floating_battery_exempt)
                // Keep the control above the system gesture area before injecting a real tap.
                onView(withId(R.id.reliability_limits)).perform(scrollTo())
                onView(withId(R.id.background_settings)).perform(click())
                awaitFocus(scenario, false)
                assertEquals("Background settings must target SAO's own app details", 1, appDetails.hits)
                shell("input keyevent KEYCODE_BACK"); awaitFocus(scenario, true)
                onView(withId(R.id.reliability_limits)).perform(scrollTo())
                screenshot("floating-background-reliability")
            } finally { instrumentation.removeMonitor(battery); instrumentation.removeMonitor(appDetails) }
            scenario.moveToState(Lifecycle.State.CREATED); exempt(false)
            scenario.onActivity { assertEquals(it.getString(R.string.floating_battery_exempt), it.findViewById<Chip>(R.id.battery_optimization).text) }
            scenario.moveToState(Lifecycle.State.RESUMED); awaitBattery(scenario, R.string.floating_battery_optimized)
        }
    }

    @Test fun reliabilityControlsFitAtLargeTextInPortraitAndLandscape() {
        val scale = shell("settings get system font_scale")
        try {
            exempt(true); shell("settings put system font_scale 2.0")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openSettings(scenario)
                for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    await("The requested orientation must settle") {
                        var ready = false
                        scenario.onActivity { activity -> ready = activity.resources.configuration.orientation ==
                            if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) android.content.res.Configuration.ORIENTATION_PORTRAIT
                            else android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                        ready
                    }
                    awaitBattery(scenario, R.string.floating_battery_exempt)
                    for (id in listOf(R.id.battery_optimization, R.id.background_settings)) {
                        onView(withId(id)).perform(scrollTo())
                        scenario.onActivity { activity ->
                            val chip = activity.findViewById<Chip>(id)
                            val row = chip.parent as android.view.ViewGroup
                            val title = row.getChildAt(0) as TextView
                            assertTrue(title.width > 0 && title.right <= chip.left)
                            assertTrue(chip.right <= row.width)
                            val visible = Rect(); assertTrue(chip.getGlobalVisibleRect(visible))
                            assertEquals(chip.height, visible.height())
                        }
                    }
                    screenshot("floating-background-large-${if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "portrait" else "landscape"}")
                }
            }
        } finally {
            if (scale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $scale")
            shell("wm user-rotation lock 0")
        }
    }
}
