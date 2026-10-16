package one.njk.sao.floating

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.navigation.fragment.NavHostFragment
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import one.njk.sao.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class FloatingSettingsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8) }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(label, condition())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250) // Let RenderThread present the newly scrolled viewport.
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(app.filesDir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun inlineSettingsRemainReachableAtLargeTextInLandscape() {
        val oldScale = shell("settings get system font_scale").trim()
        try {
            shell("settings put system font_scale 2.0")
            shell("wm user-rotation lock 1")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                    host.navController.navigate(R.id.settingsFragment)
                }
                await("Settings must use large text in landscape") {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.resources.configuration.let { it.fontScale >= 1.9f &&
                            it.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE } &&
                            activity.findViewById<View>(R.id.auto_hide)?.width?.let { it > 0 } == true
                    }
                    ready
                }
                for (id in listOf(R.id.show_on_launch, R.id.auto_hide, R.id.prefer_stickers, R.id.overlay_permission, R.id.accessibility_permission)) {
                    onView(withId(id)).perform(scrollTo())
                    scenario.onActivity { activity ->
                        val chip = activity.findViewById<Chip>(id)
                        val row = chip.parent as android.view.ViewGroup
                        val label = row.getChildAt(0) as TextView
                        assertTrue("Large labels must have space", label.width > 0)
                        assertTrue("Control must not overlap its label", label.right <= chip.left)
                        assertTrue("Control must stay within its row", chip.right <= row.width)
                        assertTrue("Control must remain reachable", chip.getGlobalVisibleRect(Rect()))
                    }
                    if (id == R.id.auto_hide) screenshot("inline-settings-large-text-landscape.png")
                }
                onView(withText(R.string.about_sao)).perform(scrollTo())
            }
        } finally {
            if (oldScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $oldScale")
            shell("wm user-rotation lock 0")
        }
    }

    @Test fun mainSettingsExposeInlineControlsAndRestoreThemAfterNavigationAndRecreation() {
        val preferences = FloatingPreferences(app)
        val previous = runBlocking { preferences.current() }
        runBlocking { preferences.autoHide(false); preferences.preferStickers(false); preferences.idleSeconds(3) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                    host.navController.navigate(R.id.settingsFragment)
                }
                await("Inline settings must render saved preferences") {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<Chip>(R.id.auto_hide)?.let { !it.isChecked && it.text == activity.getString(R.string.disabled) } == true
                        assertNotNull(activity.findViewById<View>(R.id.nsfw_toggle))
                        assertNotNull(activity.findViewById<View>(R.id.overlay_permission))
                        assertEquals(activity.getString(R.string.floating_settings_accessibility_description),
                            activity.findViewById<TextView>(R.id.accessibility_description).text.toString())
                    }
                    ready
                }
                onView(withId(R.id.accessibility_description)).perform(scrollTo())
                if (InstrumentationRegistry.getArguments().containsKey("dockShots")) screenshot("inline-settings-accessibility.png")
                onView(withId(R.id.auto_hide)).perform(scrollTo(), click())
                await("Inline dim toggle must persist") { runBlocking { preferences.current().autoHide } }
                onView(withId(R.id.delay_five)).perform(scrollTo(), click())
                await("Inline delay must persist") { runBlocking { preferences.current().idleSeconds == 5 } }
                onView(withId(R.id.prefer_stickers)).perform(scrollTo(), click())
                await("Inline sticker preference must persist") { runBlocking { preferences.current().preferStickers } }
                scenario.onActivity { activity ->
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                    assertTrue(host.navController.popBackStack())
                    host.navController.navigate(R.id.settingsFragment)
                }
                scenario.recreate()
                await("Recreated settings must restore all controls") {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<Chip>(R.id.auto_hide).isChecked &&
                            activity.findViewById<Chip>(R.id.delay_five).isChecked &&
                            activity.findViewById<Chip>(R.id.prefer_stickers).isChecked
                    }
                    ready
                }
                onView(withId(R.id.start_stop)).perform(scrollTo())
                screenshot("inline-settings-portrait.png")
                onView(withId(R.id.usage_permission)).perform(scrollTo())
                screenshot("inline-settings-permissions.png")
            }
        } finally {
            runBlocking {
                preferences.autoHide(previous.autoHide); preferences.preferStickers(previous.preferStickers)
                preferences.idleSeconds(previous.idleSeconds)
            }
        }
    }

    @Test fun overlayGlyphIsCenteredWithinTheVisibleButton() {
        val preferences = FloatingPreferences(app)
        val previous = runBlocking { preferences.current() }
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        runBlocking { preferences.autoHide(false) }
        try {
            ActivityScenario.launch(FloatingSettingsActivity::class.java).use { settings ->
                settings.onActivity { it.findViewById<View>(R.id.start_stop).performClick() }
                var icon: FloatingActionButton? = null
                await("Overlay must be measured") {
                    instrumentation.runOnMainSync {
                        icon = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>()
                            .singleOrNull()?.takeIf { it.width > 0 }
                    }
                    icon != null
                }
                instrumentation.runOnMainSync {
                    val button = icon!!
                    val body = Rect().also(button::getMeasuredContentRect)
                    val glyph = RectF(button.drawable.bounds)
                    button.imageMatrix.mapRect(glyph)
                    glyph.offset(button.paddingLeft.toFloat(), button.paddingTop.toFloat())
                    val report = JSONObject().put("view_width", button.width).put("view_height", button.height)
                        .put("body", body.toShortString()).put("glyph", glyph.toShortString())
                        .put("offset_x", glyph.centerX() - body.exactCenterX())
                        .put("offset_y", glyph.centerY() - body.exactCenterY())
                    android.util.Log.i("SaoFloatingSettings", report.toString())
                    assertTrue("Emoji must be horizontally centered: $report", abs(glyph.centerX() - body.exactCenterX()) <= 1f)
                    assertTrue("Emoji must be vertically centered: $report", abs(glyph.centerY() - body.exactCenterY()) <= 1f)
                }
                screenshot("floating-icon-centered.png")
            }
        } finally {
            app.stopService(Intent(app, FloatingService::class.java))
            runBlocking { preferences.autoHide(previous.autoHide) }
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
