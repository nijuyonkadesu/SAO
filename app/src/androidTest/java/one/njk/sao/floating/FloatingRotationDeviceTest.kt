package one.njk.sao.floating

import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class FloatingRotationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val preferences = FloatingPreferences(app)
    private var fixedRotation = "default"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        var stableSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            if (predicate()) {
                if (stableSince == 0L) stableSince = now
                // Rotation's surface transform can briefly match before the window settles.
                // Sample a stable interval instead of asserting a second, racing predicate.
                if (now - stableSince >= 200) return
            } else stableSince = 0L
            SystemClock.sleep(40)
        }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(app.filesDir, "rotation-failure.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        File(app.filesDir, "rotation-failure-windows.txt").writeText(shell("dumpsys window windows"))
        fail(label)
    }
    private fun icon(): FloatingActionButton? {
        var result: FloatingActionButton? = null
        instrumentation.runOnMainSync { result = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull() }
        return result
    }
    private fun prepare() {
        fixedRotation = shell("wm fixed-to-user-rotation")
        shell("wm fixed-to-user-rotation enabled")
        shell("wm user-rotation lock 0"); shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
    }
    private fun cleanup(old: FloatingOptions) {
        app.stopService(Intent(app, FloatingService::class.java))
        await("Service must stop") { !FloatingSession.active.value }
        // The independent host must not remain in the test APK's activity stack and intercept
        // ActivityScenario's temporary EmptyActivity when later lifecycle tests resume SAO.
        shell("am broadcast -a ${RotationHostActivity.CLOSE} -p ${instrumentation.context.packageName}")
        shell("wm user-rotation free"); shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        shell("wm fixed-to-user-rotation $fixedRotation")
        runBlocking { preferences.bubblePosition(old.edgeEnd, old.verticalPosition); preferences.autoHide(old.autoHide); preferences.idleSeconds(old.idleSeconds) }
    }
    private fun screenBounds(): android.graphics.Rect? {
        // Both View and Accessibility window coordinates can retain transforms while the
        // owner activity is stopped. Use the server frame that matches the rendered surface.
        // This is a test-only oracle for the pinned API 35 emulator, independent of SAO's cache.
        val window = shell("dumpsys window windows").split(Regex("(?m)^  Window #")).firstOrNull {
            "package=${app.packageName} appop=SYSTEM_ALERT_WINDOW" in it && "ty=APPLICATION_OVERLAY" in it &&
                "SAO dismiss target" !in it &&
                "isOnScreen=true" in it && "isVisible=true" in it
        } ?: return null
        val frame = Regex("Frames:.*?\\bframe=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").find(window) ?: return null
        val values = frame.groupValues.drop(1).map(String::toInt)
        return android.graphics.Rect(values[0], values[1], values[2], values[3])
    }
    @Suppress("DEPRECATION") // Independent physical-display oracle, outside SAO's window/config cache.
    private fun assertPlacement(end: Boolean, tucked: Boolean, fraction: Float, rotation: Int) {
        var diagnostic = ""
        await("Icon must stay on its edge after rotation=$rotation, end=$end, tucked=$tucked") {
            val frame = screenBounds() ?: return@await false
            var correct = false
            instrumentation.runOnMainSync {
                val size = Point(); app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
                val bounds = android.graphics.Rect(0, 0, size.x, size.y)
                if ((bounds.width() > bounds.height()) != (rotation % 2 == 1)) return@runOnMainSync
                val view = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull() ?: return@runOnMainSync
                val insets = view.context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val params = view.layoutParams as WindowManager.LayoutParams
                val margin = (8 * view.resources.displayMetrics.density).toInt()
                val expectedX = if (tucked) {
                    if (end) bounds.width() - insets.right - view.width / 2 else insets.left - view.width / 2
                } else if (end) bounds.width() - insets.right - view.width - margin else insets.left + margin
                val top = insets.top + margin
                val range = maxOf(0, bounds.height() - insets.bottom - view.height - 2 * margin - top)
                val expectedY = top + (range * fraction).toInt()
                val visible = minOf(bounds.width() - insets.right, frame.right) - maxOf(insets.left, frame.left)
                val edge = if (end) frame.right >= bounds.width() - insets.right else frame.left <= insets.left
                diagnostic = "screen=${bounds.width()}x${bounds.height()}, window=$frame, params=${params.x},${params.y}, expected=$expectedX,$expectedY"
                correct = abs(params.x - expectedX) <= 3 && abs(params.y - expectedY) <= 3 &&
                    abs(frame.left - expectedX) <= 3 && abs(frame.top - expectedY) <= 3 &&
                    frame.top >= 0 && frame.bottom <= bounds.height() && (edge || !tucked) &&
                    abs(visible - if (tucked) view.width / 2 else view.width) <= 3 &&
                    abs(view.alpha - if (tucked) .35f else 1f) < .03f
            }
            if (!correct && diagnostic.isNotEmpty()) android.util.Log.d("SAORotationTest", diagnostic)
            correct
        }
    }
    @Test fun visibleAndTuckedIconsKeepBothEdgesAndHeightAcrossRepeatedRotationsWithoutPreferenceWrites() {
        val old = runBlocking { preferences.current() }; prepare()
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { scenario ->
                for (end in listOf(true, false)) for (tucked in listOf(false, true)) {
                    runBlocking { preferences.bubblePosition(end, .72f); preferences.autoHide(tucked); preferences.idleSeconds(3) }
                    scenario.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                    await("Icon must be ready") { icon() != null && FloatingSession.iconState.value ==
                        if (tucked) FloatingSession.IconState.TUCKED else FloatingSession.IconState.VISIBLE }
                    val file = File(app.filesDir, "datastore/floating_picker.preferences_pb")
                    val before = file.readBytes(); val modified = file.lastModified()
                    for (rotation in listOf(1, 0, 3, 0)) {
                        shell("wm user-rotation lock $rotation")
                        assertPlacement(end, tucked, .72f, rotation)
                    }
                    assertArrayEquals("Rotation must not persist pixel coordinates", before, file.readBytes())
                    assertEquals(modified, file.lastModified())
                    scenario.onActivity { it.findViewById<android.view.View>(R.id.stop_floating).performClick() }
                    await("Icon must stop between cases") { !FloatingSession.active.value && icon() == null }
                }
            }
        } finally { cleanup(old) }
    }
    @Test fun backgroundOverlaySurvivesHostRotationsAndRotationWhileHiddenForPicker() {
        val old = runBlocking { preferences.current() }; prepare()
        val access = dagger.hilt.EntryPoints.get(app.applicationContext, FloatingTestAccess::class.java)
        val selectedSource = runBlocking { access.sources().current().selectedId }
        runBlocking { preferences.bubblePosition(true, .72f); preferences.autoHide(true); preferences.idleSeconds(3); access.sources().select(null) }
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                await("Icon must tuck") { icon() != null && FloatingSession.iconState.value == FloatingSession.IconState.TUCKED }
                // Reproduce the ordinary overlay use case with another process as the visible host.
                shell("am start -n ${instrumentation.context.packageName}/${RotationHostActivity::class.java.name}")
                for (rotation in listOf(1, 0, 3, 0)) {
                    shell("wm user-rotation lock $rotation"); assertPlacement(true, true, .72f, rotation)
                }
                // Let the system rotation animation/input transform finish before physical tapping.
                SystemClock.sleep(500)
                val frame = requireNotNull(screenBounds()); var x = 0; var y = 0
                instrumentation.runOnMainSync {
                    val size = Point()
                    @Suppress("DEPRECATION")
                    app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
                    x = (maxOf(0, frame.left) + minOf(size.x, frame.right)) / 2
                    y = frame.centerY()
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
                        .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                await("Remaining half must open the picker after rotation") { FloatingSession.pickerVisible && icon() == null }
                shell("wm user-rotation lock 1")
                await("Rotated picker must finish recreating before Back") {
                    var resumed = false
                    instrumentation.runOnMainSync {
                        resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<PickerActivity>()
                            .any { it.windowManager.maximumWindowMetrics.bounds.let { bounds -> bounds.width() > bounds.height() } }
                    }
                    resumed
                }
                instrumentation.waitForIdleSync()
                shell("input keyevent KEYCODE_BACK")
                await("Icon must return after rotating the hidden picker") { !FloatingSession.pickerVisible && icon() != null && FloatingSession.iconState.value == FloatingSession.IconState.TUCKED }
                assertPlacement(true, true, .72f, 1)
                shell("wm user-rotation lock 0"); assertPlacement(true, true, .72f, 0)
            }
        } finally { cleanup(old); runBlocking { access.sources().select(selectedSource) } }
    }

    @Test fun rotationCancelsHeldTouchWithoutStoppingServiceOrSavingOldCoordinates() {
        val old = runBlocking { preferences.current() }; prepare()
        runBlocking { preferences.bubblePosition(true, .72f); preferences.autoHide(false) }
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                await("Icon must appear") { icon() != null && FloatingSession.iconState.value == FloatingSession.IconState.VISIBLE }
                val view = icon()!!
                val file = File(app.filesDir, "datastore/floating_picker.preferences_pb")
                val before = file.readBytes(); val modified = file.lastModified()
                val down = SystemClock.uptimeMillis()
                instrumentation.runOnMainSync {
                    val event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, 0)
                    try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                shell("wm user-rotation lock 1")
                assertPlacement(true, false, .72f, 1)
                SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
                assertTrue("A touch begun before rotation must not stop the service afterward", FloatingSession.active.value)
                instrumentation.runOnMainSync {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, view.width / 2f, view.height / 2f, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                assertFalse("The invalidated touch must not open the picker", FloatingSession.pickerVisible)
                assertArrayEquals(before, file.readBytes()); assertEquals(modified, file.lastModified())
                shell("wm user-rotation lock 0"); assertPlacement(true, false, .72f, 0)
            }
        } finally { cleanup(old) }
    }

    @Test fun landscapeLockPortraitUnlockAndReturnToLandscapeKeepEdgeAndHeight() {
        val old = runBlocking { preferences.current() }; prepare()
        runBlocking { preferences.bubblePosition(true, .72f); preferences.autoHide(true); preferences.idleSeconds(3) }
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                await("Icon must tuck") { icon() != null && FloatingSession.iconState.value == FloatingSession.IconState.TUCKED }
                shell("am start -n ${instrumentation.context.packageName}/${RotationHostActivity::class.java.name}")
                val file = File(app.filesDir, "datastore/floating_picker.preferences_pb")
                val before = file.readBytes(); val modified = file.lastModified()
                repeat(3) { cycle ->
                    val landscape = if (cycle % 2 == 0) 1 else 3
                    shell("wm user-rotation lock $landscape"); assertPlacement(true, true, .72f, landscape)
                    shell("input keyevent KEYCODE_SLEEP")
                    await("Lock must detach the icon") { !app.getSystemService(android.os.PowerManager::class.java).isInteractive && icon() == null }
                    shell("wm user-rotation lock 0")
                    shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
                    await("Unlock must reattach the icon") { icon() != null }
                    assertPlacement(true, true, .72f, 0)
                    shell("wm user-rotation lock $landscape"); assertPlacement(true, true, .72f, landscape)
                }
                assertArrayEquals("Lock/rotation must not write position", before, file.readBytes())
                assertEquals(modified, file.lastModified())
            }
        } finally { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard"); cleanup(old) }
    }

}
