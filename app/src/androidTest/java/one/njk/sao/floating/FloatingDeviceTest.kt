package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.njk.sao.MainActivity
import one.njk.sao.R
import one.njk.sao.floating.nativebridge.INativeBridge
import one.njk.sao.floating.nativebridge.INativeStickerClient
import one.njk.sao.floating.nativebridge.NativeBridgeService
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FloatingDeviceTest {
    @get:org.junit.Rule val marquees = PickerMarqueeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Before fun resetBrowsingPosition() {
        dagger.hilt.EntryPoints.get(app.applicationContext, FloatingTestAccess::class.java).browseSession().reset()
    }

    @Test fun samplesAndRenditionsAreValidAndAvailableOffline() = runBlocking {
        val source = LocalMediaSource(app)
        val items = source.all()
        assertEquals(7, items.size)
        assertEquals(1, items.count { it.animated })
        val exports = MediaExports(app, source)
        for (item in items.filterNot { it.animated }) {
            val png = exports.prepare(item, Rendition.STICKER_PNG)
            assertEquals("${app.packageName}.fileprovider", png.uri.authority)
            assertEquals("image/png", app.contentResolver.getType(png.uri))
            BitmapFactory.decodeFile(png.file.path).also { bitmap ->
                assertEquals(512, bitmap.width); assertEquals(512, bitmap.height)
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0))); bitmap.recycle()
            }
            val sticker = exports.prepare(item, Rendition.STICKER_WEBP)
            assertEquals("image/webp", app.contentResolver.getType(sticker.uri))
            assertTrue(sticker.file.length() <= 100 * 1024)
            val media = exports.prepare(item, Rendition.MEDIA)
            assertEquals(item.mimeType, app.contentResolver.getType(media.uri))
            assertArrayEquals(source.openOriginal(item).use { it.readBytes() }, media.file.readBytes())
            assertTrue(media.file.length() > 0)
            assertArrayEquals(media.file.readBytes(), app.contentResolver.openInputStream(media.uri)!!.use { it.readBytes() })
        }
        val gif = items.single { it.animated }
        val exported = exports.prepare(gif, Rendition.MEDIA)
        assertEquals("image/gif", app.contentResolver.getType(exported.uri))
        assertArrayEquals(source.openOriginal(gif).use { it.readBytes() }, exported.file.readBytes())
        try { exports.prepare(gif, Rendition.STICKER_PNG); fail("GIF must not silently become a still sticker") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun launcherAndPickerRecreationPreserveTheirOwnState() {
        assertEquals(MainActivity::class.java.name, app.packageManager.getLaunchIntentForPackage(app.packageName)!!.component!!.className)
        assertFalse(FloatingSession.active.value)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.search).setText("❤️")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("❤️", activity.findViewById<android.widget.EditText>(R.id.search).text.toString())
                assertFalse(FloatingSession.active.value)
            }
        }
        assertFalse(FloatingSession.pickerVisible)
    }

    @Test fun pickerResizesForTheKeyboardAndRestoresAfterSearch() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            var originalHeight = 0
            scenario.onActivity { activity ->
                originalHeight = activity.findViewById<android.view.View>(R.id.picker_panel).layoutParams.height
                activity.findViewById<android.view.View>(R.id.search_toggle).performClick()
                val search = activity.findViewById<android.widget.EditText>(R.id.search)
                search.requestFocus()
                WindowCompat.getInsetsController(activity.window, search).show(WindowInsetsCompat.Type.ime())
            }
            fun awaitHeight(condition: (Int) -> Boolean) {
                var height = originalHeight
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { height = it.findViewById<android.view.View>(R.id.picker_panel).layoutParams.height }
                    if (condition(height)) return
                    SystemClock.sleep(50)
                }
                fail("Picker height was $height; original was $originalHeight")
            }
            val imeDeadline = SystemClock.elapsedRealtime() + 5000
            var imeHeight = 0
            while (imeHeight == 0 && SystemClock.elapsedRealtime() < imeDeadline) {
                scenario.onActivity { activity ->
                    imeHeight = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                }
                SystemClock.sleep(50)
            }
            assertTrue("Keyboard must be visible", imeHeight > 0)
            scenario.onActivity { activity ->
                val panel = activity.findViewById<android.view.View>(R.id.picker_panel)
                val location = IntArray(2); panel.getLocationOnScreen(location)
                assertTrue(panel.layoutParams.height <= originalHeight)
                assertTrue("Action dock must fit above the keyboard", location[1] + panel.height <= activity.windowManager.maximumWindowMetrics.bounds.height() - imeHeight)
            }
            scenario.onActivity { activity ->
                val search = activity.findViewById<android.widget.EditText>(R.id.search)
                search.setText("heart")
                search.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
            }
            awaitHeight { it == originalHeight }
            scenario.onActivity { assertEquals("heart", it.findViewById<android.widget.EditText>(R.id.search).text.toString()) }
        }
    }

    @Test fun searchSelectsTheWholeQueryAndLuckyHasContrastInBothThemes() = runBlocking {
        val access = dagger.hilt.EntryPoints.get(app.applicationContext, FloatingTestAccess::class.java)
        val previousSource = access.sources().current()
        val previousNightMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        try {
            access.sources().select(null)
            for ((label, mode) in listOf("light" to androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                "dark" to androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode) }
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.findViewById<android.view.View>(R.id.search_toggle).performClick()
                        activity.findViewById<android.widget.EditText>(R.id.search).apply {
                            setText("happy 😂")
                            onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
                        }
                    }
                    var ready = false
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (!ready && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            ready = !activity.findViewById<android.view.View>(R.id.search_field).isShown &&
                                activity.findViewById<com.google.android.material.chip.Chip>(R.id.search_lucky).isEnabled
                        }
                        SystemClock.sleep(40)
                    }
                    assertTrue(ready)
                    scenario.onActivity { it.findViewById<android.view.View>(R.id.search_toggle).performClick() }
                    SystemClock.sleep(600) // Also check selection after the IME opens.
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<android.widget.EditText>(R.id.search)
                        assertTrue(search.hasFocus())
                        assertEquals(0, search.selectionStart)
                        assertEquals(search.text.length, search.selectionEnd)
                        val lucky = activity.findViewById<com.google.android.material.chip.Chip>(R.id.search_lucky)
                        assertTrue("Lucky must be visible in $label mode", lucky.isShown)
                        val tint = lucky.chipIconTint!!.getColorForState(lucky.drawableState, Color.TRANSPARENT)
                        val surface = com.google.android.material.color.MaterialColors.getColor(lucky,
                            com.google.android.material.R.attr.colorSurfaceContainerLow)
                        assertTrue("Lucky's icon needs 3:1 contrast in $label mode",
                            androidx.core.graphics.ColorUtils.calculateContrast(tint, surface) >= 3.0)
                    }
                    if (InstrumentationRegistry.getArguments().containsKey("dockShots")) {
                        SystemClock.sleep(1_000) // Let the async preview and keyboard finish presenting.
                        val bitmap = instrumentation.uiAutomation.takeScreenshot()
                        java.io.File(app.filesDir, "search-selected-$label.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    instrumentation.sendStringSync("love")
                    scenario.onActivity { assertEquals("love", it.findViewById<android.widget.EditText>(R.id.search).text.toString()) }
                }
            }
        } finally {
            instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previousNightMode) }
            access.sources().restore(previousSource); access.browseSession().reset()
        }
    }

    @Test fun userStartedBubbleHidesForThePickerAndStopsOnHold() {
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        fun bubble(): FloatingActionButton? {
            var result: FloatingActionButton? = null
            instrumentation.runOnMainSync {
                result = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull()
            }
            return result
        }
        fun awaitState(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("Overlay state did not settle", condition())
        }
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        runBlocking { FloatingPreferences(app).autoHide(false) }
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { settings ->
                settings.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                awaitState { FloatingSession.active.value && bubble() != null }
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use {
                    awaitState { FloatingSession.pickerVisible && bubble() == null }
                }
                awaitState { !FloatingSession.pickerVisible && bubble() != null }
                val location = IntArray(2)
                val icon = bubble()!!
                instrumentation.runOnMainSync {
                    icon.getLocationOnScreen(location)
                    location[0] += icon.width / 2; location[1] += icon.height / 2
                }
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, location[0].toFloat(), location[1].toFloat(), 0).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true)) } finally { down.recycle() }
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, location[0].toFloat(), location[1].toFloat(), 0).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                try { instrumentation.uiAutomation.injectInputEvent(up, true) } finally { up.recycle() }
                awaitState { !FloatingSession.active.value && bubble() == null }
                settings.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                awaitState { FloatingSession.active.value && bubble() != null }
                shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW ignore")
                awaitState { !FloatingSession.active.value && bubble() == null }
            }
        } finally {
            app.stopService(Intent(app, FloatingService::class.java))
            runBlocking { FloatingPreferences(app).autoHide(true) }
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }

    @Test fun largeTextLandscapeDockFitsAboveTheKeyboard() {
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { d ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(d).use { it.readBytes() }
        } }
        shell("settings put system font_scale 2.0")
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                var landscape = false; val deadline = SystemClock.elapsedRealtime() + 5000
                while (!landscape && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { val bounds = it.windowManager.maximumWindowMetrics.bounds; landscape = bounds.width() > bounds.height() }
                    SystemClock.sleep(50)
                }
                assertTrue(landscape)
                scenario.onActivity { it.findViewById<android.view.View>(R.id.search_toggle).performClick() }
                var ime = 0; val imeDeadline = SystemClock.elapsedRealtime() + 5000
                while (ime == 0 && SystemClock.elapsedRealtime() < imeDeadline) {
                    scenario.onActivity { ime = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
                    SystemClock.sleep(50)
                }
                assertTrue(ime > 0)
                SystemClock.sleep(500); instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val panel = activity.findViewById<android.view.View>(R.id.picker_panel)
                    val panelLocation = IntArray(2); panel.getLocationOnScreen(panelLocation)
                    ime = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val available = activity.windowManager.maximumWindowMetrics.bounds.height() - ime
                    for (id in listOf(R.id.search_toggle, R.id.close, R.id.copy_action, R.id.paste_action, R.id.sticker_action, R.id.media_action)) {
                        val view = activity.findViewById<android.view.View>(id); val location = IntArray(2); view.getLocationOnScreen(location)
                        assertTrue("Control $id must be rendered", view.isShown && view.height >= (40 * activity.resources.displayMetrics.density).toInt())
                        assertTrue("Control $id must stay within the panel", location[1] >= panelLocation[1] && location[1] + view.height <= panelLocation[1] + panel.height)
                        assertTrue("Control $id must stay above keyboard", location[1] + view.height <= available)
                    }
                }
                shell("screencap -p /data/local/tmp/sao-large-landscape-ime.png")
            }
        } finally { shell("settings put system font_scale 1.0"); shell("wm user-rotation free") }
    }

    @Test fun idleIconStaysHalfVisibleTouchableAndRestoresFromNotification() {
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { d ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(d).use { it.readBytes() }
        } }
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("Overlay state must settle", condition())
        }
        fun icon(): FloatingActionButton? {
            var found: FloatingActionButton? = null
            instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull() }
            return found
        }
        val preferences = FloatingPreferences(app)
        runBlocking { preferences.autoHide(true); preferences.idleSeconds(3) }
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { settings ->
                for (end in listOf(true, false)) {
                    runBlocking { preferences.bubblePosition(end, .35f) }
                    settings.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                    waitFor { FloatingSession.iconState.value == FloatingSession.IconState.TUCKED }
                    val bubble = icon()!!; val location = IntArray(2); var x = 0; var y = 0
                    instrumentation.runOnMainSync {
                        assertEquals(.35f, bubble.alpha, .03f)
                        bubble.getLocationOnScreen(location)
                        val screenWidth = app.resources.displayMetrics.widthPixels
                        val left = maxOf(0, location[0]); val right = minOf(screenWidth, location[0] + bubble.width)
                        assertTrue("Half the icon must remain visible: end=$end x=${location[0]} width=${bubble.width} screen=$screenWidth visible=${right-left} windowX=${(bubble.layoutParams as android.view.WindowManager.LayoutParams).x}", right-left in (bubble.width/2-3)..(bubble.width/2+3))
                        x = (left + right)/2; y = location[1] + bubble.height/2
                    }
                    // Touch the actual remaining half, including on the left edge with negative window X.
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                    waitFor { FloatingSession.pickerVisible && icon() == null }
                    shell("input keyevent KEYCODE_BACK")
                    waitFor { !FloatingSession.pickerVisible && FloatingSession.iconState.value == FloatingSession.IconState.TUCKED }
                    val notifications = app.getSystemService(android.app.NotificationManager::class.java).activeNotifications
                    notifications.single { it.id == 21 }.notification.actions.single {
                        it.title.toString() == app.getString(R.string.floating_show)
                    }.actionIntent.send()
                    waitFor { FloatingSession.iconState.value == FloatingSession.IconState.VISIBLE }
                    val restored = icon()!!
                    instrumentation.runOnMainSync {
                        restored.getLocationOnScreen(location)
                        assertEquals(1f, restored.alpha, .01f)
                        assertTrue(location[0] >= 0 && location[0] + restored.width <= app.resources.displayMetrics.widthPixels)
                    }
                    settings.onActivity { it.findViewById<android.view.View>(R.id.stop_floating).performClick() }
                    waitFor { FloatingSession.iconState.value == FloatingSession.IconState.STOPPED }
                }
            }
        } finally {
            app.stopService(Intent(app, FloatingService::class.java)); shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
            runBlocking { preferences.bubblePosition(true, .35f) }
        }
    }

    @Test fun searchEmojiAndBothDeliveryCheckmarksSurviveClosingAndRecreation() = runBlocking {
        val preferences = FloatingPreferences(app); preferences.preferStickers(true)
        val marker = UUID.randomUUID().toString()
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("Picker state must settle", condition())
        }
        fun ready(scenario: ActivityScenario<PickerActivity>, filtered: Boolean): Boolean {
            var ready = false
            scenario.onActivity { activity ->
                val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                val chips = activity.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filters)
                ready = (grid.adapter as MediaCarouselAdapter).itemCount == (if (filtered) 1 else 7) && chips.childCount == 7 &&
                    activity.findViewById<android.view.View>(R.id.copy_action).isEnabled
            }
            return ready
        }
        fun verify(scenario: ActivityScenario<PickerActivity>) {
            await { ready(scenario, true) }
            scenario.onActivity { activity ->
                assertEquals("love", activity.findViewById<android.widget.EditText>(R.id.search).text.toString())
                val group = activity.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filters)
                assertEquals("❤️", group.findViewById<com.google.android.material.chip.Chip>(group.checkedChipId).text.toString())
                val mode = activity.findViewById<com.google.android.material.chip.Chip>(R.id.prefer_stickers)
                assertEquals(activity.getString(R.string.picker_media), mode.text.toString())
                assertTrue(mode.isChecked && mode.isCheckedIconVisible)
                val clip = activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!
                assertEquals(marker, clip.getItemAt(0).text.toString())
                assertTrue(activity.findViewById<android.view.View>(R.id.copy_action).isEnabled)
            }
        }
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                await { ready(scenario, false) }
                scenario.onActivity { activity ->
                    activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("marker", marker))
                    activity.findViewById<android.widget.EditText>(R.id.search).setText("love")
                    val chips = activity.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filters)
                    (0 until chips.childCount).map { chips.getChildAt(it) as com.google.android.material.chip.Chip }
                        .single { it.text.toString() == "❤️" }.performClick()
                    val mode = activity.findViewById<com.google.android.material.chip.Chip>(R.id.prefer_stickers)
                    assertTrue(mode.isChecked && mode.isCheckedIconVisible)
                    assertEquals(activity.getString(R.string.picker_sticker), mode.text.toString())
                    mode.performClick()
                }
                await {
                    var media = false
                    scenario.onActivity { media = it.findViewById<com.google.android.material.chip.Chip>(R.id.prefer_stickers).text.toString() == it.getString(R.string.picker_media) }
                    media
                }
                verify(scenario)
            }
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
                verify(reopened); reopened.recreate(); verify(reopened)
            }
            val expected = MediaQuery("love", "❤️")
            assertEquals(expected, dagger.hilt.EntryPoints.get(app.applicationContext, FloatingTestAccess::class.java).browseSession().snapshot().query)
            assertEquals(PickerBrowseState(), PickerBrowseSession().snapshot()) // A fresh process session has no durable browsing state.
        } finally { preferences.preferStickers(true) }
    }

    @Test fun browsingNeverChangesThePreferencesFile() = runBlocking {
        val preferences = FloatingPreferences(app)
        preferences.preferStickers(true) // Finish initialization and any one-time migration before the baseline.
        val file = java.io.File(app.filesDir, "datastore/floating_picker.preferences_pb")
        assertTrue(file.exists())
        val original = file.readBytes(); val modified = file.lastModified()
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            var loaded = false; val deadline = SystemClock.elapsedRealtime() + 5000
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity {
                    val grid = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                    loaded = (grid.adapter as MediaCarouselAdapter).itemCount == 7
                }
                SystemClock.sleep(50)
            }
            assertTrue(loaded)
            scenario.onActivity {
                val search = it.findViewById<android.widget.EditText>(R.id.search)
                search.setText("h"); search.setText("ha"); search.setText("happy"); search.setText("")
                val chips = it.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filters)
                (chips.getChildAt(0) as com.google.android.material.chip.Chip).performClick()
                (chips.getChildAt(0) as com.google.android.material.chip.Chip).performClick()
                it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid).fling(6000, 0)
            }
            SystemClock.sleep(800)
            scenario.recreate()
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { SystemClock.sleep(500) }
        assertArrayEquals(original, file.readBytes())
        assertEquals("Browsing must not rewrite settings", modified, file.lastModified())
    }

    @Test fun savedStateRestoresTheTaskWithoutRetainingDeliveryState() = runBlocking {
        val source = LocalMediaSource(app); val dispatchers = FloatingDispatchers()
        val handle = androidx.lifecycle.SavedStateHandle(mapOf(
            PickerViewModel.QUERY_TEXT to "love", PickerViewModel.QUERY_FILTER to "❤️", PickerViewModel.FOCUSED_ID to "love"))
        val session = PickerBrowseSession().apply { remember(PickerBrowseState(MediaQuery("stale"), "happy")) }
        lateinit var model: PickerViewModel
        instrumentation.runOnMainSync {
            model = PickerViewModel(source, FloatingPreferences(app), MediaExports(app, source), ClipboardPublisher(app, dispatchers), session, handle)
            assertEquals(MediaQuery("love", "❤️"), model.query.value)
            assertEquals("love", model.focusedId)
            assertNull(model.selection.value)
            assertEquals(session.snapshot(), PickerBrowseState(model.query.value, model.focusedId))
            androidx.lifecycle.ViewModelStore().apply { put("restored", model); clear() }
        }
        assertEquals(PickerBrowseState(), PickerBrowseSession().snapshot())
    }

    @Test fun lockedPhoneRemovesTheIconAndUnlockRestoresItsTuckedState() {
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { d ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(d).bufferedReader().use { it.readText().trim() }
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("Lock/unlock state must settle", condition())
        }
        fun hasIcon(): Boolean {
            var found = false
            instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().any { it is FloatingActionButton } }
            return found
        }
        val oldTimeout = shell("settings get secure lock_screen_lock_after_timeout")
        val oldDisabled = shell("locksettings get-disabled")
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("locksettings set-disabled false"); shell("settings put secure lock_screen_lock_after_timeout 0")
        runBlocking { FloatingPreferences(app).autoHide(true); FloatingPreferences(app).idleSeconds(3) }
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { settings ->
                settings.onActivity { it.findViewById<android.view.View>(R.id.start_stop).performClick() }
                await { FloatingSession.iconState.value == FloatingSession.IconState.TUCKED && hasIcon() }
                shell("input keyevent KEYCODE_SLEEP")
                await { !app.getSystemService(android.os.PowerManager::class.java).isInteractive && !hasIcon() }
                assertTrue(FloatingSession.active.value)
                shell("input keyevent KEYCODE_WAKEUP")
                await { app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }
                assertFalse(hasIcon())
                shell("wm dismiss-keyguard")
                await { !app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                    FloatingSession.iconState.value == FloatingSession.IconState.TUCKED && hasIcon() }
                instrumentation.runOnMainSync {
                    val icon = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().single()
                    assertEquals(.35f, icon.alpha, .03f)
                }
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            app.stopService(Intent(app, FloatingService::class.java))
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
            shell("locksettings set-disabled $oldDisabled")
            if (oldTimeout == "null") shell("settings delete secure lock_screen_lock_after_timeout")
            else shell("settings put secure lock_screen_lock_after_timeout $oldTimeout")
        }
    }

    @Test fun flingsTraverseSeveralCardsAndPositionSurvivesReopenResizeAndRecreation() {
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("Carousel state must settle", condition())
        }
        fun focused(scenario: ActivityScenario<PickerActivity>): Int {
            var position = -1
            scenario.onActivity { activity ->
                val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                val helper = grid.onFlingListener as com.google.android.material.carousel.CarouselSnapHelper
                helper.findSnapView(grid.layoutManager!!)?.let { position = grid.layoutManager!!.getPosition(it) }
            }
            return position
        }
        var remembered = -1
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await { focused(scenario) == 0 }
            scenario.onActivity { activity ->
                val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                assertTrue(grid.fling(grid.maxFlingVelocity, 0))
            }
            await {
                var idle = false
                scenario.onActivity { idle = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid).scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE }
                idle && focused(scenario) > 1
            }
            remembered = focused(scenario)
            scenario.recreate(); await { focused(scenario) == remembered }
            scenario.onActivity { it.findViewById<android.view.View>(R.id.search_toggle).performClick() }
            SystemClock.sleep(600)
            scenario.onActivity { it.findViewById<android.view.View>(R.id.search_toggle).performClick() }
            await { focused(scenario) == remembered }
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
            await { focused(reopened) == remembered }
            reopened.onActivity { it.findViewById<android.widget.EditText>(R.id.search).setText("happy") }
            await { focused(reopened) == 0 }
            reopened.onActivity { it.findViewById<android.widget.EditText>(R.id.search).setText("") }
            await { focused(reopened) == 0 }
        }
    }

    @Test fun tappingCopiesImmediatelyButBrowsingSearchingAndRecreationDoNotCopy() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            var adapter: MediaCarouselAdapter? = null
            fun clip(): String? {
                var uri: String? = null
                scenario.onActivity { uri = it.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri?.toString() }
                return uri
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (adapter?.itemCount != 7 && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { adapter = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid).adapter as MediaCarouselAdapter }
                SystemClock.sleep(50)
            }
            assertEquals(7, adapter!!.itemCount)
            scenario.onActivity { it.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("test marker", UUID.randomUUID().toString())) }
            val before = clip()
            assertNull(adapter!!.selectedId)
            scenario.onActivity { activity ->
                assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.search_field).visibility)
                val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                grid.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                assertFalse(activity.isFinishing)
            }
            val selected = adapter!!.currentList.first().id
            val copyDeadline = SystemClock.elapsedRealtime() + 5000
            while (clip()?.contains("$selected-media") != true && SystemClock.elapsedRealtime() < copyDeadline) SystemClock.sleep(50)
            assertTrue(clip()?.contains("$selected-media") == true)
            assertEquals(selected, adapter!!.selectedId)
            val copied = clip(); assertNotEquals(before, copied)
            scenario.onActivity { activity -> activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid).scrollToPosition(3) }
            SystemClock.sleep(300); assertEquals(copied, clip()); assertEquals(selected, adapter!!.selectedId)
            scenario.recreate(); assertEquals(copied, clip())
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.search_toggle).performClick()
                activity.findViewById<android.widget.EditText>(R.id.search).setText("missing")
                activity.findViewById<android.widget.EditText>(R.id.search).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
                assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.search_field).visibility)
            }
            SystemClock.sleep(400); assertEquals(copied, clip())
            scenario.onActivity { activity ->
                val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                assertNull((grid.adapter as MediaCarouselAdapter).selectedId)
                assertFalse(activity.findViewById<android.view.View>(R.id.copy_action).isEnabled)
                activity.findViewById<android.view.View>(R.id.search_toggle).performClick()
                assertEquals("missing", activity.findViewById<android.widget.EditText>(R.id.search).text.toString())
            }
        }
    }

    @Test fun dockCopiesTheCenteredItemWithoutATapAndFollowsReopenedPosition() {
        val marker = UUID.randomUUID().toString()
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
            assertTrue(label, condition())
        }
        fun clip(scenario: ActivityScenario<PickerActivity>): String? {
            var uri: String? = null
            scenario.onActivity { uri = it.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri?.toString() }
            return uri
        }
        fun centered(scenario: ActivityScenario<PickerActivity>): MediaItem? {
            var item: MediaItem? = null
            scenario.onActivity { activity ->
                val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                val focal = (grid.onFlingListener as com.google.android.material.carousel.CarouselSnapHelper).findSnapView(grid.layoutManager!!)
                item = focal?.let { (grid.getChildViewHolder(it) as MediaCarouselAdapter.Holder).item }
                if (!activity.findViewById<android.view.View>(R.id.copy_action).isEnabled ||
                    activity.findViewById<android.widget.TextView>(R.id.selection_title).text.toString() != item?.title) item = null
            }
            return item
        }
        var third: MediaItem? = null
        var lastCopy: String? = null
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await("The initial centered item must be actionable without selecting it") { centered(scenario) != null }
            val first = centered(scenario)!!
            scenario.onActivity { activity ->
                val model = androidx.lifecycle.ViewModelProvider(activity)[PickerViewModel::class.java]
                assertNull(model.selection.value)
                assertFalse(activity.findViewById<android.view.View>(R.id.paste_action).isEnabled)
                assertTrue(activity.findViewById<android.view.View>(R.id.media_action).isEnabled)
                activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("marker", marker))
                activity.findViewById<android.view.View>(R.id.copy_action).performClick()
            }
            await("Copy must use the centered image") { clip(scenario)?.contains("${first.id}-media") == true }
            lastCopy = clip(scenario)
            scenario.onActivity { it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid).scrollToPosition(3) }
            await("The dock must follow the next center") { centered(scenario)?.id?.let { it != first.id } == true }
            val second = centered(scenario)!!
            scenario.onActivity { activity ->
                assertEquals(first.id, androidx.lifecycle.ViewModelProvider(activity)[PickerViewModel::class.java].selection.value?.id)
                val copied = activity.getString(androidx.lifecycle.ViewModelProvider(activity)[PickerViewModel::class.java].copyStatus.value)
                assertFalse("The previous item's copy feedback must not remain",
                    activity.findViewById<android.widget.TextView>(R.id.selection_message).text.startsWith(copied))
            }
            assertEquals("Browsing must not copy", lastCopy, clip(scenario))
            scenario.onActivity { it.findViewById<android.view.View>(R.id.copy_action).performClick() }
            await("Copy must no longer use the previously tapped/copied item") { clip(scenario)?.contains("${second.id}-media") == true }
            lastCopy = clip(scenario)
            scenario.onActivity { it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid).scrollToPosition(5) }
            await("A third item must be centered before dismissal") { centered(scenario)?.id?.let { it != second.id } == true }
            third = centered(scenario)
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
            await("Reopening must restore an actionable center without a selection") { centered(reopened)?.id == third!!.id }
            assertEquals("Reopening must not copy", lastCopy, clip(reopened))
            reopened.onActivity { assertNull(androidx.lifecycle.ViewModelProvider(it)[PickerViewModel::class.java].selection.value) }
            reopened.onActivity { it.findViewById<android.view.View>(R.id.copy_action).performClick() }
            await("Copy must use the restored center") { clip(reopened)?.contains("${third!!.id}-media") == true }
            val copied = clip(reopened)
            reopened.onActivity { it.findViewById<android.widget.EditText>(R.id.search).setText("no-such-reaction") }
            await("An empty result must disable delivery") {
                var empty = false
                reopened.onActivity { activity ->
                    empty = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid).adapter!!.itemCount == 0 &&
                        !activity.findViewById<android.view.View>(R.id.copy_action).isEnabled && !activity.findViewById<android.view.View>(R.id.media_action).isEnabled
                }; empty
            }
            assertEquals(copied, clip(reopened))
        }
    }

    @Test fun animatedSelectionCopiesTheOriginalGifAndDisablesStickerDelivery() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.whatsapp", null)).use { scenario ->
            var selected = false; val deadline = SystemClock.elapsedRealtime() + 5000
            while (!selected && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                    val adapter = grid.adapter as MediaCarouselAdapter
                    val position = adapter.currentList.indexOfFirst { it.animated }
                    if (position >= 0) { grid.scrollToPosition(position); grid.findViewHolderForAdapterPosition(position)?.itemView?.let { it.performClick(); selected = true } }
                }
                SystemClock.sleep(50)
            }
            assertTrue(selected)
            var gifCopied = false
            while (!gifCopied && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity -> gifCopied = activity.getSystemService(android.content.ClipboardManager::class.java).primaryClipDescription?.hasMimeType("image/gif") == true }
                SystemClock.sleep(50)
            }
            assertTrue(gifCopied)
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<android.view.View>(R.id.sticker_action).isEnabled)
                assertTrue(activity.findViewById<android.view.View>(R.id.media_action).isEnabled)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test fun obsoleteClipboardRequestCannotPublish() = runBlocking {
        val source = LocalMediaSource(app); val item = source.all().first()
        val exported = MediaExports(app, source).prepare(item, Rendition.MEDIA)
        val request = ClipboardRequest(); val obsolete = request.next(); request.next()
        assertFalse(ClipboardPublisher(app, FloatingDispatchers()).publish(item, exported, request, obsolete))
    }

    @Test fun configuredForkReceivesTheStickerUnderItsExactPackage() = runBlocking {
        val preferences = FloatingPreferences(app); val previous = preferences.current().telegramPackages
        val fork = instrumentation.context.packageName
        preferences.telegramPackages(previous + fork)
        val received = CountDownLatch(1); val response = AtomicReference<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { response.set(intent); received.countDown() }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(TelegramForkProbeActivity.RESULT), ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, fork, null)).use { scenario ->
                var selected = false
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (!selected && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                        grid.findViewHolderForAdapterPosition(0)?.itemView?.let { it.performClick(); selected = true }
                    }
                    if (!selected) SystemClock.sleep(50)
                }
                assertTrue(selected)
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = it.findViewById<android.view.View>(R.id.sticker_action).isEnabled }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("Configured fork must have the Telegram sticker action", ready)
                scenario.onActivity { it.findViewById<android.view.View>(R.id.sticker_action).performClick() }
                assertTrue(received.await(10, TimeUnit.SECONDS))
                assertEquals(fork, response.get().getStringExtra("received_package"))
                assertEquals(TelegramDelivery.DOCUMENT_MIME, response.get().getStringExtra("mime"))
                assertEquals("image/webp", response.get().getStringExtra("provider_mime"))
                assertEquals("sticker_document", response.get().getStringExtra("classification"))
                assertEquals(512, response.get().getIntExtra("width", 0)); assertEquals(512, response.get().getIntExtra("height", 0))
                assertTrue(response.get().getIntExtra("length", 0) <= 100 * 1024)
                assertNull(response.get().getStringExtra("error")); assertTrue(response.get().getIntExtra("length", 0) > 0)
            }
        } finally { app.unregisterReceiver(receiver); preferences.telegramPackages(previous) }
    }

    @Test fun slowObsoleteExportDoesNotBlockMainOrOverwriteLatestSelection() = runBlocking {
        val local = LocalMediaSource(app); val items = local.all()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = object : MediaSource by local {
            override suspend fun original(item: MediaItem): java.io.InputStream {
                if (item.id == items[0].id) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                return local.openOriginal(item)
            }
            override suspend fun raster(item: MediaItem) = original(item)
        }
        // Use fresh immutable export IDs so cached files cannot bypass the slow reader.
        val first = items[0].copy(id = "slow_" + UUID.randomUUID().toString().replace("-", ""))
        val last = items[1].copy(id = "latest_" + UUID.randomUUID().toString().replace("-", ""))
        val wrapper = object : MediaSource by slow {
            override suspend fun original(item: MediaItem): java.io.InputStream = slow.original(
                if (item.id == first.id) items[0] else items[1])
            override suspend fun raster(item: MediaItem) = original(item)
        }
        val dispatchers = FloatingDispatchers()
        lateinit var model: PickerViewModel
        instrumentation.runOnMainSync { model = PickerViewModel(wrapper, FloatingPreferences(app), MediaExports(app, wrapper, dispatchers), ClipboardPublisher(app, dispatchers)) }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            try {
                scenario.onActivity { model.select(first, MessagingTarget.OTHER) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val callback = CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post { model.select(last, MessagingTarget.OTHER); callback.countDown() }
                assertTrue("Main must remain responsive while file IO is stalled", callback.await(500, TimeUnit.MILLISECONDS))
                release.countDown()
                var uri: String? = null; val deadline = SystemClock.elapsedRealtime() + 5000
                while (uri?.contains(last.id) != true && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { uri = it.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri?.toString() }
                    SystemClock.sleep(50)
                }
                assertTrue(uri?.contains(last.id) == true)
                assertEquals(last.id, model.selection.value?.id)
            } finally {
                release.countDown()
                instrumentation.runOnMainSync { androidx.lifecycle.ViewModelStore().apply { put("test", model); clear() } }
            }
        }
    }

    @Test fun aReceiverUnderAnotherUidCanReadOnlyWhenGivenARealUriGrant() = runBlocking {
        val source = LocalMediaSource(app)
        val exported = MediaExports(app, source).prepare(source.all().first(), Rendition.STICKER_PNG)
        for (grantRead in listOf(false, true)) verifyReceiverAccess(exported, grantRead)
    }

    private fun verifyReceiverAccess(exported: ExportedMedia, grantRead: Boolean) {
        val nonce = UUID.randomUUID().toString()
        val ready = CountDownLatch(1)
        val result = AtomicReference<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("nonce") == nonce) { result.set(intent); ready.countDown() }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(MediaReceiverProbeActivity.RESULT), ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                scenario.onActivity { activity ->
                    val send = Intent(if (grantRead) Intent.ACTION_SEND else "one.njk.sao.test.PROBE")
                        .setComponent(ComponentName(instrumentation.context.packageName, MediaReceiverProbeActivity::class.java.name))
                        .setType(exported.mimeType).putExtra(Intent.EXTRA_STREAM, exported.uri)
                        .putExtra("nonce", nonce).putExtra("return_package", app.packageName)
                    if (grantRead) {
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newUri(app.contentResolver, "probe", exported.uri)
                    }
                    activity.startActivity(send)
                }
                assertTrue("Receiver did not return", ready.await(15, TimeUnit.SECONDS))
                assertNotEquals(Process.myUid(), result.get().getIntExtra("receiver_uid", -1))
                if (grantRead) {
                    assertNull(result.get().getStringExtra("error"))
                    assertEquals("image/png", result.get().getStringExtra("mime"))
                    assertEquals(exported.file.length().toInt(), result.get().getIntExtra("length", -1))
                } else assertEquals("SecurityException", result.get().getStringExtra("error"))
            }
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun nativeBridgeRejectsClientsOutsideTheScopedHostUid() {
        val connected = CountDownLatch(1)
        var bridge: INativeBridge? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { bridge = INativeBridge.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(app.bindService(Intent(app, NativeBridgeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            for (scoped in listOf("com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.discord")) try {
                bridge!!.registerClient(scoped, object : INativeStickerClient.Stub() {
                    override fun setActive(active: Boolean, generation: Long) = Unit
            override fun snapshot(beginSelection: Boolean): Bundle = Bundle.EMPTY
                    override fun insert(uri: Uri, mimeType: String, session: String, requestId: Long) = false
                })
                fail("An unscoped UID must not register a native client")
            } catch (_: SecurityException) { }
        } finally { app.unbindService(connection) }
    }
}
