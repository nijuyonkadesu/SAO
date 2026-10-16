package one.njk.sao.floating

import android.app.UiAutomation
import android.content.*
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.inspector.WindowInspector
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

/** Real Android accessibility, clipboard and separate-UID receiver, with no Telegram login/messages. */
class PasteAssistDeviceTest {
    @get:Rule val marquees = PickerMarqueeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val hostPackage = instrumentation.context.packageName
    private val preferences = FloatingPreferences(app)
    private val state = AtomicReference<Bundle>()
    private lateinit var previous: FloatingOptions
    private var oldServices: String? = null
    private var oldEnabled = 0
    private var oldActive = false
    private lateinit var automation: UiAutomation
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { state.set(intent.extras ?: Bundle.EMPTY) }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue(label, condition())
    }
    @Before fun setup() = runBlocking {
        previous = preferences.current(); oldActive = FloatingSession.active.value
        EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java).browseSession().reset()
        oldServices = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        oldEnabled = Settings.Secure.getInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        preferences.telegramPackages(previous.telegramPackages + hostPackage); preferences.pasteAssistance(true)
        FloatingSession.active(true)
        ContextCompat.registerReceiver(app, receiver, IntentFilter(PasteHostActivity.STATE), ContextCompat.RECEIVER_EXPORTED)
        val component = ComponentName(app, PasteAssistService::class.java).flattenToString()
        val services = oldServices.orEmpty().split(':').filter(String::isNotBlank).toMutableSet().apply { add(component) }
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        await("Accessibility service must connect") { PasteAssistService.connected() }
    }
    @After fun cleanup() = runBlocking {
        command("finish")
        FloatingSession.active(false)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled)
        preferences.telegramPackages(previous.telegramPackages); preferences.pasteAssistance(previous.pasteAssistance)
        FloatingSession.active(oldActive)
        app.unregisterReceiver(receiver); automation.dropShellPermissionIdentity()
        EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java).browseSession().reset()
    }
    private fun command(value: String) { app.sendBroadcast(Intent(PasteHostActivity.COMMAND).setPackage(hostPackage).putExtra("command", value)) }
    private fun host(neverFocused: Boolean = false) {
        state.set(null)
        app.startActivity(Intent().setComponent(ComponentName(hostPackage, PasteHostActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("return_package", app.packageName).putExtra("never_focused", neverFocused))
        await("Host must display its composer") { state.get()?.let { it.getBoolean("window_focused") && (neverFocused || it.getBoolean("focused")) } == true }
        SystemClock.sleep(150)
    }
    private fun capture(): PasteAssistService.Capture {
        var result: PasteAssistService.Capture? = null
        await("The currently focused composer must be captured") {
            result = runBlocking { PasteAssistService.prepareTarget() }; result?.token != null
        }
        assertEquals(hostPackage, result!!.packageName)
        return result!!
    }
    private fun picker(capture: PasteAssistService.Capture, action: (ActivityScenario<PickerActivity>) -> Unit = {}) {
        val item = runBlocking { LocalMediaSource(app).all().first { !it.hasMotion } }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, hostPackage, capture.token)).use { scenario ->
            lateinit var model: PickerViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[PickerViewModel::class.java]
                model.select(item, MessagingTarget.TELEGRAM)
            }
            await("The real media URI must be copied and Paste enabled") {
                var ready = false
                scenario.onActivity { ready = it.findViewById<View>(one.njk.sao.R.id.paste_action).isEnabled }
                ready && model.copyStatus.value in setOf(one.njk.sao.R.string.picker_telegram_sticker_copied, one.njk.sao.R.string.picker_copied)
            }
            action(scenario)
            scenario.onActivity { it.findViewById<View>(one.njk.sao.R.id.paste_action).performClick() }
            await("The queued request must reach an actual outcome") { PasteAssistService.results.value?.token == capture.token }
        }
    }
    @Test fun previouslyFocusedComposerReceivesRealMediaAfterLosingFocus() {
        host(); capture()
        command("unfocus"); await("Composer must be unfocused") { !state.get().getBoolean("focused") }
        val retained = capture()
        picker(retained)
        assertEquals(PasteOutcome.DISPATCHED, PasteAssistService.results.value!!.outcome)
        await("The receiver must stage readable media exactly once") { state.get().getInt("pastes") == 1 && state.get().getInt("previews") == 1 }
        assertTrue(state.get().getInt("bytes") > 0)
        assertTrue(state.get().getString("mime") in setOf("image/webp", "image/jpeg"))
        assertFalse("Consumed requests must not replay", PasteAssistService.requestPaste(retained.token, hostPackage))
    }
    @Test fun waitsForEditorFocusAfterTheHostWindowHasAlreadyReturned() {
        host(); capture(); command("delay")
        await("Host must delay focus restoration") { state.get().getBoolean("block") }
        val retained = capture()
        picker(retained)
        assertEquals(PasteOutcome.DISPATCHED, PasteAssistService.results.value!!.outcome)
        await("Paste must wait for the delayed editor") { state.get().getInt("pastes") == 1 }
        assertTrue(state.get().getLong("pasted") - state.get().getLong("returned") >= 450)
    }
    @Test fun replacementEditorCannotReceiveTheOriginalRequest() {
        host(); val original = capture()
        picker(original) { command("replace"); SystemClock.sleep(150) }
        assertEquals(PasteOutcome.UNAVAILABLE, PasteAssistService.results.value!!.outcome)
        assertEquals(0, state.get().getInt("pastes"))
    }
    @Test fun anotherFocusedEditableFieldCannotStealTheRequest() {
        host(); val original = capture()
        picker(original) { command("other"); SystemClock.sleep(150) }
        assertEquals(PasteOutcome.UNAVAILABLE, PasteAssistService.results.value!!.outcome)
        assertEquals(0, state.get().getInt("pastes"))
    }
    @Test fun aFalseHostResultDoesNotDuplicateAnAlreadyStagedPreview() {
        host(); val original = capture(); command("reject")
        picker(original)
        assertEquals(PasteOutcome.UNCONFIRMED, PasteAssistService.results.value!!.outcome)
        SystemClock.sleep(600)
        assertEquals(1, state.get().getInt("pastes")); assertEquals(1, state.get().getInt("previews"))
    }
    @Test fun unfocusedEditorWithoutPreviousFocusIsNotGuessed() {
        host(neverFocused = true)
        var result: PasteAssistService.Capture? = null
        await("The unfocused host window must be available") {
            result = runBlocking { PasteAssistService.prepareTarget() }; result?.packageName == hostPackage
        }
        assertFalse(state.get().getBoolean("focused"))
        assertNull("Do not guess an arbitrary editable field", result?.token)
    }
    @Test fun unavailableFocusStopsWithoutPastingIntoAnotherField() {
        host(); capture(); command("deny")
        await("The original editor must refuse focus") { state.get().getBoolean("block") }
        val retained = capture()
        picker(retained)
        assertEquals(PasteOutcome.UNAVAILABLE, PasteAssistService.results.value!!.outcome)
        assertFalse(state.get().getBoolean("focused"))
        assertEquals(0, state.get().getInt("pastes"))
    }
    @Test fun actualFloatingIconCapturesTheHostBeforeOpeningThePicker() = runBlocking {
        fun shell(value: String) { automation.executeShellCommand(value).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        } }
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        preferences.autoHide(false)
        try {
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { settings ->
                settings.onActivity { ContextCompat.startForegroundService(it, Intent(it, FloatingService::class.java)) }
                var icon: FloatingActionButton? = null
                await("The real floating icon must appear") {
                    instrumentation.runOnMainSync { icon = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull() }
                    icon != null
                }
                host(); capture(); command("unfocus")
                await("The editor must lose focus before icon press") { !state.get().getBoolean("focused") }
                instrumentation.runOnMainSync { icon!!.performClick() }
                var picker: PickerActivity? = null
                await("An icon press must open the real picker") {
                    instrumentation.runOnMainSync {
                        picker = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PickerActivity>().singleOrNull()
                    }
                    picker != null
                }
                try {
                    await("On-demand capture must enable Paste for the original host") {
                        var ready = false
                        instrumentation.runOnMainSync { ready = picker!!.findViewById<View>(one.njk.sao.R.id.paste_action).isEnabled }
                        ready
                    }
                    instrumentation.runOnMainSync { assertNull("Paste must require no image tap", ViewModelProvider(picker!!)[PickerViewModel::class.java].selection.value) }
                    instrumentation.runOnMainSync { picker!!.findViewById<View>(one.njk.sao.R.id.paste_action).performClick() }
                    await("The original host must stage the selected media") { state.get().getInt("previews") == 1 }
                    assertTrue(state.get().getInt("bytes") > 0)
                    assertEquals(1, state.get().getInt("pastes"))
                } finally { instrumentation.runOnMainSync { picker?.finish() } }
            }
        } finally {
            app.stopService(Intent(app, FloatingService::class.java))
            preferences.autoHide(previous.autoHide)
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }

    @Test fun largeTextContextAmazingKeepsDockGeometryAndScrollsLongText() {
        val original = Settings.System.getFloat(app.contentResolver, Settings.System.FONT_SCALE, 1f)
        fun scale(value: Float) { automation.executeShellCommand("settings put system font_scale $value").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        } }
        try { scale(2f); contextAmazingKeepsDockGeometryAndScrollsLongText() }
        finally { scale(original) }
    }

    @Test fun contextAmazingKeepsDockGeometryAndScrollsLongText() = runBlocking {
        val access = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
        val oldSource = access.sources().current()
        val png = app.assets.open("floating/happy.png").use { it.readBytes() }
        val gif = app.assets.open("floating/hello.gif").use { it.readBytes() }
        val longName = "Amazing reaction with a wonderfully expressive face and a very long descriptive filename "
        val items = org.json.JSONArray().apply { repeat(4) { index -> put(org.json.JSONObject()
            .put("id", java.util.UUID(7, index.toLong()).toString()).put("type", "IMAGE")
            .put("originalFileName", "$longName$index.${if (index == 1) "gif" else "png"}")
            .put("originalMimeType", if (index == 1) "image/gif" else "image/png")
            .put("updatedAt", "2026-10-15T00:00:00Z")) } }
        val searches = java.util.concurrent.CopyOnWriteArrayList<org.json.JSONObject>()
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/api/tags" -> okhttp3.mockwebserver.MockResponse().setBody("[]")
                        path == "/api/server/features" -> okhttp3.mockwebserver.MockResponse().setBody("""{"search":true,"smartSearch":true,"ocr":true}""")
                        path == "/api/search/smart" -> {
                            searches += org.json.JSONObject(request.body.readUtf8())
                            okhttp3.mockwebserver.MockResponse().setBody(org.json.JSONObject().put("assets", org.json.JSONObject().put("items", items)).toString())
                        }
                        path.endsWith("/thumbnail") || path.endsWith("/original") -> {
                            val motion = path.contains(java.util.UUID(7, 1).toString())
                            okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", if (motion) "image/gif" else "image/png")
                                .setBody(okio.Buffer().write(if (motion) gif else png))
                        }
                        else -> okhttp3.mockwebserver.MockResponse().setResponseCode(400)
                    }
                }
            }
            start()
        }
        try {
            access.immichSettings().save(ImmichConfig(server.url("/").toString(), "dock-text-fixture"))
            access.browseSession().remember(PickerBrowseState(MediaQuery("amazing", mode = SearchMode.CONTEXT)))
            host(); val original = capture()
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, hostPackage, original.token)).use { scenario ->
                // A running marquee is deliberately not idle; dispatch on Main without waiting for it.
                fun onPicker(action: (PickerActivity) -> Unit) = scenario.onMainActivity(action)
                try {
                    fun text(id: Int): String {
                        var value = ""; onPicker { value = it.findViewById<android.widget.TextView>(id).text.toString() }; return value
                    }
                    fun geometry(): List<Int> {
                        var value = emptyList<Int>()
                        onPicker { activity ->
                            value = listOf(one.njk.sao.R.id.carousel_area, one.njk.sao.R.id.selection_row,
                                one.njk.sao.R.id.selection_message, one.njk.sao.R.id.dock_surface).flatMap { id ->
                                activity.findViewById<View>(id).let { listOf(it.top, it.height) }
                            }
                        }; return value
                    }
                    fun screenshot(suffix: String) {
                        val label = InstrumentationRegistry.getArguments().getString("dockShots") ?: return
                        val bitmap = automation.takeScreenshot()
                        java.io.File(app.filesDir, "dock-$label-$suffix.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    fun pixels(id: Int): android.graphics.Bitmap {
                        lateinit var bitmap: android.graphics.Bitmap
                        onPicker {
                            val view = it.findViewById<View>(id)
                            bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(bitmap))
                        }; return bitmap
                    }
                    await("Context amazing must show a still image with no tip") {
                        var ready = false
                        onPicker { activity ->
                            val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.media_grid)
                            ready = grid.adapter!!.itemCount == 4 && !grid.isLayoutRequested &&
                                (grid.findViewHolderForAdapterPosition(0) as? MediaCarouselAdapter.Holder)?.binding?.preview?.drawable != null &&
                                activity.findViewById<View>(one.njk.sao.R.id.paste_action).isEnabled &&
                                activity.findViewById<android.widget.TextView>(one.njk.sao.R.id.selection_title).text.startsWith("Amazing reaction") &&
                                activity.findViewById<android.widget.TextView>(one.njk.sao.R.id.selection_message).text.isEmpty()
                        }; ready
                    }
                    SystemClock.sleep(150)
                    val empty = geometry(); screenshot("empty")
                    onPicker { it.findViewById<View>(one.njk.sao.R.id.copy_action).performClick() }
                    await("Copy status must appear") { text(one.njk.sao.R.id.selection_message).isNotEmpty() }
                    SystemClock.sleep(150)
                    val copied = geometry(); screenshot("copied")
                    onPicker { it.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.media_grid).smoothScrollToPosition(1) }
                    var trace = ""
                    try {
                        await("Centering a GIF must show its tip and clear the old copy status") {
                            val hint = text(one.njk.sao.R.id.selection_message)
                            onPicker {
                                val model = ViewModelProvider(it)[PickerViewModel::class.java]
                                val grid = it.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.media_grid)
                                val snap = (grid.onFlingListener as com.google.android.material.carousel.CarouselSnapHelper).findSnapView(grid.layoutManager!!)
                                trace = "focus=${model.state.value.items.indexOfFirst { item -> item.id == model.focusedId }}, snap=${snap?.let { grid.getChildAdapterPosition(it) }}, layout=${grid.isLayoutRequested}, computing=${grid.isComputingLayout}, message=$hint"
                            }
                            hint == app.getString(one.njk.sao.R.string.picker_gif_hint)
                        }
                    } catch (failure: AssertionError) { screenshot("failure"); throw AssertionError(trace, failure) }
                    SystemClock.sleep(150)
                    val motion = geometry(); screenshot("tip")
                    assertEquals("Copy status must not move the carousel or dock", empty, copied)
                    assertEquals("An appearing tip must not move the carousel or dock", empty, motion)
                    onPicker { it.findViewById<View>(one.njk.sao.R.id.copy_action).performClick() }
                    await("Copy feedback and the hint must both fit in the supporting line") {
                        val message = text(one.njk.sao.R.id.selection_message)
                        message.startsWith(app.getString(one.njk.sao.R.string.picker_gif_copied)) &&
                            message.endsWith(app.getString(one.njk.sao.R.string.picker_gif_hint))
                    }
                    SystemClock.sleep(150)
                    assertEquals("Combined feedback must not move the carousel or dock", empty, geometry())
                    screenshot("copied-tip")
                    assertEquals(1, searches.size); assertEquals("amazing", searches.single().getString("query"))
                    onPicker { activity ->
                        val title = activity.findViewById<View>(one.njk.sao.R.id.selection_title)
                        val hint = activity.findViewById<View>(one.njk.sao.R.id.selection_message)
                        val dock = activity.findViewById<View>(one.njk.sao.R.id.dock_surface)
                        assertSame("The tip belongs below the title", title.parent, hint.parent)
                        val gap = hint.top - title.bottom
                        val density = activity.resources.displayMetrics.density
                        assertTrue("The message needs a small gap beneath the title, gap=$gap", gap in density.toInt()..(3 * density).toInt())
                        val textBlock = activity.findViewById<View>(one.njk.sao.R.id.selection_text)
                        val chip = activity.findViewById<View>(one.njk.sao.R.id.prefer_stickers)
                        assertEquals("The two-line text block and mode chip must share a vertical center",
                            (textBlock.top + textBlock.bottom) / 2f, (chip.top + chip.bottom) / 2f, 1f)
                        val background = dock.background as com.google.android.material.shape.MaterialShapeDrawable
                        assertEquals("The toolbar must not tint the panel behind the buttons", 0,
                            android.graphics.Color.alpha(background.fillColor!!.defaultColor))
                        marquees.animate(activity)
                    }
                    val titleBefore = pixels(one.njk.sao.R.id.selection_title)
                    val tipBefore = pixels(one.njk.sao.R.id.selection_message)
                    SystemClock.sleep(1_000)
                    val titlePaused = pixels(one.njk.sao.R.id.selection_title)
                    val tipPaused = pixels(one.njk.sao.R.id.selection_message)
                    try {
                        assertTrue("The title must stay still for a reading pause", titleBefore.sameAs(titlePaused))
                        assertTrue("The tip must stay still for a reading pause", tipBefore.sameAs(tipPaused))
                    } finally { titlePaused.recycle(); tipPaused.recycle() }
                    SystemClock.sleep(1_000)
                    screenshot("scrolling")
                    val titleAfter = pixels(one.njk.sao.R.id.selection_title)
                    val tipAfter = pixels(one.njk.sao.R.id.selection_message)
                    try {
                        assertFalse("An overflowing title must visibly scroll", titleBefore.sameAs(titleAfter))
                        assertFalse("An overflowing tip must visibly scroll", tipBefore.sameAs(tipAfter))
                        assertEquals("Marquee must not change layout", motion, geometry())
                    } finally { listOf(titleBefore, tipBefore, titleAfter, tipAfter).forEach { it.recycle() } }
                } finally { onPicker { it.finish() } }
            }
        } finally { access.sources().restore(oldSource); access.browseSession().reset(); server.shutdown() }
    }
}
