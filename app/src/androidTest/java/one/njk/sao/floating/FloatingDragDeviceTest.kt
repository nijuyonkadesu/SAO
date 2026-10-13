package one.njk.sao.floating

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs

/** Real overlay input over a separate host UID, using only synthetic UI. */
class FloatingDragDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val preferences = FloatingPreferences(app)
    private lateinit var old: FloatingOptions
    private lateinit var scenario: ActivityScenario<FloatingSettingsActivity>
    private var gesture: Drag? = null
    private var fixedRotation = "default"
    private val store get() = File(app.filesDir, "datastore/floating_picker.preferences_pb")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(40) }
        val details = StringBuilder(label).append('\n')
        onMain { WindowInspector.getGlobalWindowViews().forEach { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            details.append("${view.javaClass.name} params=${params.x},${params.y},${params.width},${params.height} context=${view.context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds}\n")
        } }
        File(app.filesDir, "drag-failure-windows.txt").writeText(details.toString() + shell("dumpsys window windows"))
        File(app.filesDir, "drag-failure-display.txt").writeText(shell("dumpsys display"))
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(app.filesDir, "drag-failure.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        fail(label)
    }
    private fun icon(): FloatingActionButton? {
        var view: FloatingActionButton? = null
        onMain { view = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull() }
        return view
    }
    private fun target(): MaterialButton? {
        var view: MaterialButton? = null
        onMain { view = WindowInspector.getGlobalWindowViews().filterIsInstance<MaterialButton>().firstOrNull { it.id == R.id.floating_dismiss_target } }
        return view
    }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else instrumentation.runOnMainSync(action)
    }
    @Suppress("DEPRECATION") // Independent display oracle, never used by SAO's production geometry.
    private fun size() = Point().also { app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it) }
    private fun frame(title: String): Rect? {
        val window = shell("dumpsys window windows").split(Regex("(?m)^  Window #")).firstOrNull {
            title in it.substringBefore('\n') && "package=${app.packageName} appop=SYSTEM_ALERT_WINDOW" in it &&
                "isOnScreen=true" in it && "isVisible=true" in it
        } ?: return null
        val match = Regex("Frames:.*?\\bframe=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").find(window) ?: return null
        return match.groupValues.drop(1).map(String::toInt).let { Rect(it[0], it[1], it[2], it[3]) }
    }
    private fun center(view: View): Point {
        var point = Point()
        instrumentation.runOnMainSync {
            val params = view.layoutParams as WindowManager.LayoutParams
            point = Point(params.x + view.width / 2, params.y + view.height / 2)
        }
        return point
    }
    private inner class Drag {
        private val down = SystemClock.uptimeMillis()
        private var point = center(requireNotNull(icon()))
        var active = true
        init { send(MotionEvent.ACTION_DOWN) }
        fun send(action: Int, at: Point = point) {
            point = at
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x.toFloat(), point.y.toFloat(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Input must reach the overlay", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) active = false
        }
        fun move(at: Point) = send(MotionEvent.ACTION_MOVE, at)
    }
    private fun drag(): Drag = Drag().also { gesture = it }
    private fun showTarget(drag: Drag) {
        val screen = size(); drag.move(Point(screen.x / 2, screen.y / 3))
        await("A drag must show the dismiss target") { target() != null && frame("SAO dismiss target") != null }
    }
    private fun hover(drag: Drag) {
        drag.move(center(requireNotNull(target())))
        await("Target must respond to hovering") {
            var hovering = false
            instrumentation.runOnMainSync { hovering = target()?.text?.toString() == app.getString(R.string.floating_release_dismiss) }
            hovering
        }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(180)
        onMain {
            target()?.let {
                assertEquals("Dismiss hint must fit on one line", 1, it.lineCount)
                assertEquals("Dismiss hint must stay readable", 0, it.layout.getEllipsisCount(0))
            }
        }
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(app.filesDir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun startHost(keyboard: Boolean = false) {
        shell("am start -n ${instrumentation.context.packageName}/${RotationHostActivity::class.java.name}" + if (keyboard) " --ez show_keyboard true" else "")
        SystemClock.sleep(300)
    }
    @Before fun setup() {
        old = runBlocking { preferences.current() }
        fixedRotation = shell("wm fixed-to-user-rotation")
        shell("wm fixed-to-user-rotation enabled")
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard"); shell("wm user-rotation lock 0")
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        runBlocking { preferences.autoHide(false); preferences.bubblePosition(true, .4f) }
        scenario = ActivityScenario.launch(Intent(app, FloatingSettingsActivity::class.java))
        scenario.onActivity { it.findViewById<View>(R.id.start_stop).performClick() }
        await("Icon must be fully visible") { icon() != null && FloatingSession.iconState.value == FloatingSession.IconState.VISIBLE }
        await("The requested portrait rotation must settle before starting input") {
            val screen = size(); val frame = frame("SAO floating icon")
            screen.x < screen.y && frame != null && frame.left >= 0 && frame.right <= screen.x && frame.bottom <= screen.y
        }
        SystemClock.sleep(200)
    }
    @After fun cleanup() {
        gesture?.takeIf { it.active }?.send(MotionEvent.ACTION_CANCEL, Point(100, 100))
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        app.stopService(Intent(app, FloatingService::class.java))
        await("Both overlay windows must be removed") { !FloatingSession.active.value && icon() == null && target() == null }
        shell("am broadcast -a ${RotationHostActivity.CLOSE} -p ${instrumentation.context.packageName}")
        if (::scenario.isInitialized) scenario.close()
        shell("wm user-rotation free"); shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        shell("wm fixed-to-user-rotation $fixedRotation")
        runBlocking { preferences.bubblePosition(old.edgeEnd, old.verticalPosition); preferences.autoHide(old.autoHide); preferences.idleSeconds(old.idleSeconds) }
    }

    @Test fun dragTargetAppearsAfterMovementAndReleaseDismissesWithoutSavingDropPosition() {
        startHost(); val bytes = store.readBytes(); val modified = store.lastModified()
        val drag = drag(); SystemClock.sleep(80)
        assertNull("A tap must not show a dismiss target", target())
        showTarget(drag)
        instrumentation.runOnMainSync {
            val params = target()!!.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(0, params.fitInsetsTypes)
            assertTrue("Dismiss target must be compact", target()!!.width < size().x)
        }
        screenshot("drag-dismiss-ready.png")
        hover(drag); screenshot("drag-dismiss-hover.png")
        drag.send(MotionEvent.ACTION_UP)
        await("Dropping over Dismiss must stop the session") { !FloatingSession.active.value && icon() == null && target() == null }
        assertFalse(FloatingSession.pickerVisible)
        assertArrayEquals(bytes, store.readBytes()); assertEquals(modified, store.lastModified())
    }

    @Test fun cancelledDragRestoresAnchorAndOrdinaryDropsSnapToEachEdge() {
        startHost(); val original = requireNotNull(frame("SAO floating icon"))
        val bytes = store.readBytes(); val modified = store.lastModified()
        val cancel = drag(); showTarget(cancel); hover(cancel); cancel.send(MotionEvent.ACTION_CANCEL)
        await("Cancel must remove the target and restore the saved edge") { target() == null && frame("SAO floating icon") == original }
        assertTrue(FloatingSession.active.value); assertArrayEquals(bytes, store.readBytes()); assertEquals(modified, store.lastModified())
        for (end in listOf(false, true)) {
            val screen = size(); val drop = Point(if (end) screen.x * 3 / 4 else screen.x / 4, screen.y / 2)
            val drag = drag(); showTarget(drag); drag.move(drop); drag.send(MotionEvent.ACTION_UP)
            await("A normal drop must snap and persist only its anchor") {
                val edge = runBlocking { preferences.current().edgeEnd }
                val frame = frame("SAO floating icon") ?: return@await false
                val margin = (8 * app.resources.displayMetrics.density).toInt()
                edge == end && (if (end) abs(frame.right - (screen.x - margin)) <= 3 else abs(frame.left - margin) <= 3) && target() == null
            }
            assertTrue(FloatingSession.active.value); assertFalse(FloatingSession.pickerVisible)
        }
    }

    @Test fun rotationAndLockCancelAnArmedDismissAndIgnoreItsOldRelease() {
        startHost(); val bytes = store.readBytes(); val modified = store.lastModified()
        val rotate = drag(); showTarget(rotate); hover(rotate)
        shell("wm user-rotation lock 1")
        await("Rotation must cancel the dismissal preview") { size().x > size().y && target() == null && frame("SAO floating icon")?.let { it.top >= 0 && it.bottom <= size().y } == true }
        rotate.send(MotionEvent.ACTION_UP, Point(size().x / 2, size().y / 2))
        assertTrue(FloatingSession.active.value); assertFalse(FloatingSession.pickerVisible)
        val lock = drag(); showTarget(lock); hover(lock)
        shell("input keyevent KEYCODE_SLEEP")
        await("Lock must detach both windows") { icon() == null && target() == null }
        shell("wm user-rotation lock 0"); shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        await("Portrait unlock must restore the icon") { icon() != null && size().x < size().y }
        lock.send(MotionEvent.ACTION_UP, Point(size().x / 2, size().y / 2))
        assertTrue(FloatingSession.active.value); assertFalse(FloatingSession.pickerVisible); assertNull(target())
        assertArrayEquals(bytes, store.readBytes()); assertEquals(modified, store.lastModified())
    }

    @Test fun dismissTargetFitsAboveAnOpenKeyboardWithoutTakingEditorFocus() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        automation.serviceInfo = info
        startHost(keyboard = true)
        fun keyboard(): Rect? = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { Rect().also(it::getBoundsInScreen) }
        await("Synthetic editor must show the IME") { keyboard() != null }
        val ime = requireNotNull(keyboard())
        val drag = drag(); showTarget(drag)
        val target = requireNotNull(frame("SAO dismiss target"))
        assertTrue("Dismiss must fit above IME: target=$target, keyboard=$ime", target.bottom <= ime.top)
        assertNotNull("The overlay must leave the keyboard open", keyboard())
        screenshot("drag-dismiss-keyboard.png")
        drag.send(MotionEvent.ACTION_CANCEL)
        shell("input keyevent KEYCODE_BACK")
    }

    @Test fun largeTextLandscapeTargetFitsAndHoverKeepsItsBounds() {
        try {
            shell("settings put system font_scale 2.0"); shell("wm user-rotation lock 1")
            app.stopService(Intent(app, FloatingService::class.java))
            await("Restart with the large-text window context") { !FloatingSession.active.value && icon() == null }
            scenario.onActivity { it.findViewById<View>(R.id.start_stop).performClick() }
            await("Landscape icon must reappear") { icon() != null && size().x > size().y }
            startHost()
            val drag = drag(); showTarget(drag)
            val before = requireNotNull(frame("SAO dismiss target"))
            assertTrue(before.left >= 0 && before.right <= size().x && before.top >= 0 && before.bottom <= size().y)
            hover(drag); SystemClock.sleep(180)
            assertEquals("Hover label must not resize or reposition the target", before, frame("SAO dismiss target"))
            onMain {
                assertEquals(1, target()!!.lineCount)
                assertEquals(2f, target()!!.resources.configuration.fontScale, .01f)
            }
            screenshot("drag-dismiss-large-landscape.png")
            drag.send(MotionEvent.ACTION_CANCEL)
        } finally { shell("settings put system font_scale 1.0"); shell("wm user-rotation lock 0") }
    }
}
