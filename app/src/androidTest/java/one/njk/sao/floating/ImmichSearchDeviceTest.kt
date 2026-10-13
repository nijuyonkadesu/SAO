package one.njk.sao.floating

import android.content.ClipboardManager
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.InputDevice
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import one.njk.sao.R
import org.hamcrest.Matchers.equalTo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ImmichSearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private lateinit var server: MockWebServer
    private lateinit var profile: SourceProfile
    private var previous = SourceCatalog()
    private val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    private val peopleCalls = AtomicInteger()
    private val placesCalls = AtomicInteger()
    private val rejectPeople = AtomicBoolean()
    private val rejectRandom = AtomicBoolean()
    private val randomCalls = AtomicInteger()
    private val randomTags = AtomicBoolean()
    private val restrictedModes = AtomicBoolean()
    private val alice = "ca9a1ff5-f185-4d1d-8e5a-667a2d14bf51"
    private val bob = "ca9a1ff5-f185-4d1d-8e5a-667a2d14bf52"
    private val peopleJson get() = JSONArray().put(JSONObject().put("id", alice).put("name", "Alice"))
        .put(JSONObject().put("id", bob).put("name", "Bob"))

    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.browseSession().reset()
        val png = app.assets.open("floating/happy.png").use { it.readBytes() }
        val video = instrumentation.context.assets.open("preview-video.webm").use { it.readBytes() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/api/people" || path == "/api/search/person") {
                        peopleCalls.incrementAndGet()
                        return if (rejectPeople.get()) MockResponse().setResponseCode(403)
                        else MockResponse().setBody(if (path.endsWith("person")) peopleJson.toString()
                            else JSONObject().put("people", peopleJson).put("hasNextPage", false).toString())
                    }
                    if (path.endsWith("thumbnail")) return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                    if (path.endsWith("original")) return MockResponse().setBody(Buffer().write(if (path.contains(UUID(99, 3).toString())) video else png))
                    if (path == "/api/tags") return MockResponse().setBody(if (randomTags.get()) """[{"id":"cd59f6b0-ff74-4d47-bcb6-d02e9d3b4a34","name":"😂","value":"reactions/😂"}]""" else "[]")
                    if (path == "/api/server/features") return MockResponse().setBody(if (restrictedModes.get())
                        "{\"search\":true,\"smartSearch\":false,\"ocr\":false}" else "{\"search\":true,\"smartSearch\":true,\"ocr\":true}")
                    if (path == "/api/search/suggestions") {
                        placesCalls.incrementAndGet()
                        return MockResponse().setBody(JSONArray(listOf(when (request.requestUrl!!.queryParameter("type")) {
                        "country" -> "France"; "state" -> "Île-de-France"; else -> "Paris"
                    })).toString())
                    }
                    val query = JSONObject(request.body.clone().readUtf8())
                    requests += path to query
                    if (path == "/api/search/random") {
                        val batch = randomCalls.incrementAndGet()
                        if (rejectRandom.get()) return MockResponse().setResponseCode(401)
                        val videoOnly = query.getJSONObject("filter").getJSONObject("type").getJSONArray("in").let { it.length() == 1 && it.getString(0) == "VIDEO" }
                        val items = JSONArray()
                        repeat(query.getInt("size")) { index -> items.put(JSONObject()
                            .put("id", "baca3915-8f2e-4155-9485-${(batch * 1000 + index).toString().padStart(12, '0')}")
                            .put("type", if (videoOnly) "VIDEO" else "IMAGE")
                            .put("originalFileName", "random-$batch-$index.${if (videoOnly) "webm" else "png"}")
                            .put("originalMimeType", if (videoOnly) "video/webm" else "image/png")
                            .put("visibility", "timeline").put("updatedAt", "2026-10-12T00:00:00Z")) }
                        return MockResponse().setBody(items.toString()).setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    val filter = query.optJSONObject("filter")
                    val type = query.optString("type").ifBlank { filter?.optJSONObject("type")?.optJSONArray("in")?.takeIf { it.length() == 1 }?.getString(0).orEmpty() }
                    val items = JSONArray()
                    for (index in 0 until 4) {
                        val isVideo = index == 3
                        if (type == "VIDEO" && !isVideo || type == "IMAGE" && isVideo) continue
                        items.put(JSONObject().put("id", UUID(99, index.toLong()).toString())
                            .put("type", if (isVideo) "VIDEO" else "IMAGE")
                            .put("originalMimeType", if (isVideo) "video/webm" else "image/png")
                            .put("originalFileName", "reaction-$index.${if (isVideo) "webm" else "png"}")
                            .put("visibility", "timeline").put("updatedAt", "2026-10-12T00:00:00Z"))
                    }
                    val assets = JSONObject().put("items", items)
                    if (query.optString("query") == "paging 😂" && query.optInt("page", 1) == 1) assets.put("nextPage", "2")
                    if (filter?.optJSONObject("originalPath")?.optString("like") == "%paging%" && !query.has("cursor")) assets.put("nextCursor", "opaque-second")
                    return MockResponse().setBody(JSONObject().put("assets", assets).toString())
                }
            }
            start()
        }
        profile = SourceProfile(UUID.randomUUID().toString(), "Search fixture", ImmichConfig(server.url("/").toString(), "search-fixture-key"))
        access.sources().upsert(profile, true)
    }
    @After fun cleanup() = runBlocking { access.sources().restore(previous); access.browseSession().reset(); server.shutdown() }
    private fun model(activity: PickerActivity) = ViewModelProvider(activity)[PickerViewModel::class.java]
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8_000
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(40)
        assertTrue(label, predicate())
    }
    private fun await(scenario: ActivityScenario<PickerActivity>, label: String, predicate: (PickerActivity) -> Boolean) = await(label) {
        var done = false; scenario.onActivity { done = predicate(it) }; done
    }
    private fun clip(): String? {
        var uri: String? = null
        instrumentation.runOnMainSync { uri = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri?.toString() }
        return uri
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(app.filesDir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }

    private class ChoiceDrag(private val scenario: ActivityScenario<PickerActivity>, buttonId: Int = R.id.query_mode) {
        private var down = 0L
        private lateinit var activity: PickerActivity
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        var x = 0f
        var y = 0f
        var row = 0f
        init {
            scenario.onActivity { activity ->
                this.activity = activity
                val chip = activity.findViewById<View>(buttonId)
                val location = IntArray(2).also(chip::getLocationInWindow)
                x = location[0] + chip.width / 2f; y = location[1] + chip.height / 2f
            }
            down = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, x, y)
        }
        fun send(action: Int, atX: Float = x, atY: Float = y) {
            // onActivity waits for idle; pressed-state animations can turn a 125 ms tap
            // into a hold. Input must go directly to Main with its real timing.
            onActivityNow { activity ->
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, atX, atY, 0).let {
                    it.source = InputDevice.SOURCE_TOUCHSCREEN
                    activity.dispatchTouchEvent(it); it.recycle()
                }
            }
        }
        fun onActivityNow(action: (PickerActivity) -> Unit) = instrumentation.runOnMainSync { action(activity) }
        fun measure() = scenario.onActivity { activity ->
            val viewport = activity.findViewById<View>(R.id.hold_choice_viewport)
            val rows = activity.findViewById<android.widget.LinearLayout>(R.id.hold_choice_rows)
            row = viewport.height.toFloat() / (rows.childCount - 2)
        }
    }
    private fun readySearch(scenario: ActivityScenario<PickerActivity>, text: String = "") {
        await(scenario, "Initial browsing must load") { !model(it).state.value.loading && model(it).state.value.items.isNotEmpty() }
        scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick(); it.findViewById<EditText>(R.id.search).setText(text) }
        await(scenario, "Search type control must be ready") {
            it.findViewById<View>(R.id.query_mode).isVisible && !model(it).state.value.loading && model(it).state.value.query == model(it).query.value &&
                ViewModelProvider(it)[SearchOptionsViewModel::class.java].state.value.modes ==
                if (restrictedModes.get()) setOf(SearchMode.FILENAME, SearchMode.DESCRIPTION, SearchMode.PATH) else SearchMode.entries.toSet()
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
    }
    private fun hold(scenario: ActivityScenario<PickerActivity>, button: Int): ChoiceDrag = ChoiceDrag(scenario, button).also { drag ->
        await(scenario, "Held selector must appear") { it.findViewById<View>(R.id.hold_choice_card)?.height?.let { h -> h > 0 } == true }
        drag.measure()
    }

    @Test fun holdingModeChipLoopsThePreviewAndSearchesOnlyAfterRelease() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            readySearch(scenario, "happy 😂")
            val before = requests.size; val clipboard = clip()
            val drag = ChoiceDrag(scenario)
            SystemClock.sleep(80)
            drag.onActivityNow { assertNull(it.findViewById<View>(R.id.hold_choice_card)) }
            await(scenario, "160 ms hold must expand the chip") { it.findViewById<View>(R.id.hold_choice_card)?.height?.let { h -> h > 0 } == true }
            drag.measure(); drag.send(MotionEvent.ACTION_MOVE, atY = drag.y + drag.row * 6)
            scenario.onActivity {
                assertEquals("OCR", it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString())
                assertEquals(SearchMode.CONTEXT, model(it).query.value.mode)
                assertFalse(it.findViewById<View>(R.id.search_options_panel).isVisible)
            }
            SystemClock.sleep(300); assertEquals(before, requests.size)
            screenshot("mode-hold-loop.png")
            drag.send(MotionEvent.ACTION_MOVE, atY = drag.y - drag.row)
            scenario.onActivity { assertEquals("Filename", it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString()) }
            screenshot("mode-hold-preview.png")
            drag.send(MotionEvent.ACTION_UP, atY = drag.y - drag.row)
            await(scenario, "Release must apply Filename directly") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).query.value.mode == SearchMode.FILENAME }
            assertEquals(before + 1, requests.size)
            assertEquals("/api/search/metadata", requests.last().first)
            assertEquals("%happy 😂%", requests.last().second.getJSONObject("filter").getJSONObject("originalFileName").getString("like"))
            assertEquals(clipboard, clip())
            scenario.onActivity {
                assertNull(it.findViewById<View>(R.id.hold_choice_card))
                assertFalse(it.findViewById<View>(R.id.search_options_panel).isVisible)
                assertTrue(it.findViewById<EditText>(R.id.search).hasFocus())
                assertTrue(androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)!!.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
            }
        }
    }

    @Test fun modeDragCancellationBackAndSourceSwitchNeverApplyProvisionalChoices() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            readySearch(scenario)
            val before = requests.size
            val pending = ChoiceDrag(scenario)
            pending.onActivityNow { it.onBackPressedDispatcher.onBackPressed() }
            pending.send(MotionEvent.ACTION_UP)
            SystemClock.sleep(650) // Neither the custom timer nor View's later long-click may fire.
            scenario.onActivity { assertNull(it.findViewById<View>(R.id.hold_choice_card)); assertFalse(it.findViewById<View>(R.id.search_options_panel).isVisible) }
            fun held(): ChoiceDrag = ChoiceDrag(scenario).also { drag ->
                await(scenario, "Held wheel must appear") { it.findViewById<View>(R.id.hold_choice_card)?.height?.let { h -> h > 0 } == true }
                drag.measure(); drag.send(MotionEvent.ACTION_MOVE, atY = drag.y - drag.row)
            }
            held().send(MotionEvent.ACTION_CANCEL)
            scenario.onActivity { assertNull(it.findViewById<View>(R.id.hold_choice_card)); assertEquals(SearchMode.CONTEXT, model(it).query.value.mode) }
            val sideways = held()
            sideways.send(MotionEvent.ACTION_MOVE, atX = sideways.x + 2_000, atY = sideways.y - sideways.row)
            scenario.onActivity { assertEquals(it.getString(R.string.search_hold_cancel), it.findViewById<android.widget.TextView>(R.id.hold_choice_hint).text.toString()) }
            sideways.send(MotionEvent.ACTION_UP, atX = sideways.x + 2_000, atY = sideways.y - sideways.row)
            val back = held()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            back.send(MotionEvent.ACTION_UP)
            scenario.onActivity { assertNull(it.findViewById<View>(R.id.hold_choice_card)); assertFalse(it.findViewById<View>(R.id.search_options_panel).isVisible); assertEquals(SearchMode.CONTEXT, model(it).query.value.mode) }
            SystemClock.sleep(250); assertEquals(before, requests.size)
            val switching = held()
            runBlocking { access.sources().select(null) }
            await(scenario, "Switching source must discard the preview") { it.findViewById<View>(R.id.hold_choice_card) == null && model(it).state.value.sourceId == "local" }
            switching.send(MotionEvent.ACTION_UP)
            scenario.onActivity { assertEquals(SearchMode.CONTEXT, model(it).query.value.mode); assertFalse(it.findViewById<View>(R.id.search_options_panel).isVisible) }
        }
    }

    @Test fun modeWheelSkipsDisabledModesAndRetainsTapAndAccessibleActions() {
        restrictedModes.set(true)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            readySearch(scenario)
            scenario.onActivity { model(it).searchMode(SearchMode.FILENAME); it.findViewById<EditText>(R.id.search).setText("happy 😂") }
            await(scenario, "Filename must load") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).query.value.mode == SearchMode.FILENAME }
            val drag = ChoiceDrag(scenario)
            await(scenario, "Wheel must appear") { it.findViewById<View>(R.id.hold_choice_card)?.height?.let { h -> h > 0 } == true }
            drag.measure(); drag.send(MotionEvent.ACTION_MOVE, atY = drag.y - drag.row * 4)
            scenario.onActivity { assertEquals("Description", it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString()) }
            drag.send(MotionEvent.ACTION_UP, atY = drag.y - drag.row * 4)
            await(scenario, "Only enabled types participate in the loop") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).query.value.mode == SearchMode.DESCRIPTION }
            scenario.onActivity {
                val button = it.findViewById<View>(R.id.query_mode)
                assertTrue(button.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                assertEquals(SearchMode.PATH, model(it).query.value.mode)
                assertTrue(button.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
                assertEquals(SearchMode.DESCRIPTION, model(it).query.value.mode)
            }
            val tap = ChoiceDrag(scenario); SystemClock.sleep(125); tap.send(MotionEvent.ACTION_UP)
            await(scenario, "A short tap must retain the complete selector") { it.findViewById<View>(R.id.type_panel).isVisible }
            scenario.onActivity { assertFalse(it.findViewById<View>(R.id.mode_context).isEnabled); assertFalse(it.findViewById<View>(R.id.mode_ocr).isEnabled) }
        }
    }

    @Test fun modeHoldFitsAtLargeTextAndRotationDiscardsThePreview() {
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }
        val oldScale = shell("settings get system font_scale")
        try {
            shell("settings put system font_scale 2.0"); shell("wm user-rotation lock 0")
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                readySearch(scenario)
                val drag = ChoiceDrag(scenario)
                await(scenario, "Large text wheel must appear") { it.findViewById<View>(R.id.hold_choice_card)?.height?.let { h -> h > 0 } == true }
                drag.measure(); drag.send(MotionEvent.ACTION_MOVE, atY = drag.y - drag.row)
                screenshot("mode-hold-large-text.png")
                scenario.onActivity {
                    val card = it.findViewById<View>(R.id.hold_choice_card)
                    val visible = android.graphics.Rect().also(it.window.decorView::getWindowVisibleDisplayFrame)
                    val position = IntArray(2).also(card::getLocationOnScreen)
                    assertTrue(position[0] >= visible.left); assertTrue(position[1] >= visible.top)
                    assertTrue(position[0] + card.width <= visible.right); assertTrue(position[1] + card.height <= visible.bottom)
                    assertEquals(SearchMode.CONTEXT, model(it).query.value.mode)
                    it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                await(scenario, "Rotation must cancel the active preview") { it.findViewById<View>(R.id.hold_choice_card) == null && it.windowManager.maximumWindowMetrics.bounds.let { bounds -> bounds.width() > bounds.height() } }
                scenario.onActivity { assertEquals(SearchMode.CONTEXT, model(it).query.value.mode) }
            }
        } finally {
            if (oldScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $oldScale")
            shell("wm user-rotation lock 0")
        }
    }

    @Test fun filterHoldAppliesMediaWithoutLosingPeoplePlacesOrChangingClipboard() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            readySearch(scenario, "happy 😂")
            scenario.onActivity { model(it).searchConstraints(SearchConstraints(profile.identity,
                listOf(SearchPerson(alice, "Alice", profile.identity)), "France", "Île-de-France", "Paris")) }
            await(scenario, "Existing filters must apply") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value }
            val before = requests.size; val copied = clip()
            val drag = hold(scenario, R.id.search_filters)
            drag.send(MotionEvent.ACTION_MOVE, atY = drag.y + drag.row * 5) // All -> Images, wrapping backward.
            scenario.onActivity {
                assertEquals("Images", it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString())
                assertEquals(it.getString(R.string.search_filters), it.findViewById<android.widget.TextView>(R.id.hold_choice_title).text.toString())
                assertEquals(SearchMedia.ALL, model(it).query.value.constraints.media)
            }
            SystemClock.sleep(250); assertEquals(before, requests.size)
            screenshot("filters-hold-preview.png")
            drag.send(MotionEvent.ACTION_UP, atY = drag.y + drag.row * 5)
            await(scenario, "Images must apply without another panel") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).query.value.constraints.media == SearchMedia.IMAGES }
            assertEquals(before + 1, requests.size); assertEquals("IMAGE", requests.last().second.getString("type"))
            scenario.onActivity {
                val query = model(it).query.value
                assertEquals("happy 😂", query.text); assertEquals(alice, query.constraints.people.single().id)
                assertEquals("Paris", query.constraints.city); assertEquals(SearchMode.CONTEXT, query.mode)
                assertFalse(it.findViewById<View>(R.id.search_options_panel).isVisible)
            }
            assertEquals(copied, clip())
            val all = hold(scenario, R.id.search_filters)
            all.send(MotionEvent.ACTION_UP, atY = all.y + all.row) // Images -> All media; retains the other filters.
            await(scenario, "All media must keep the other filters") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).query.value.constraints.media == SearchMedia.ALL }
            scenario.onActivity { assertEquals(alice, model(it).query.value.constraints.people.single().id); assertEquals("Paris", model(it).query.value.constraints.city) }
        }
    }

    @Test fun filterHoldOpensAdditionalInputOnlyAfterChoosingPeopleOrPlaces() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            readySearch(scenario)
            val people = hold(scenario, R.id.search_filters)
            people.send(MotionEvent.ACTION_MOVE, atY = people.y + people.row * 3)
            scenario.onActivity { assertEquals(it.getString(R.string.search_hold_choose_people), it.findViewById<android.widget.TextView>(R.id.hold_choice_hint).text.toString()) }
            SystemClock.sleep(250); assertEquals(0, peopleCalls.get())
            people.send(MotionEvent.ACTION_UP, atY = people.y + people.row * 3)
            await(scenario, "People requires an inline selection") { it.findViewById<View>(R.id.people_panel).isVisible && it.findViewById<RecyclerView>(R.id.people_list).adapter!!.itemCount == 2 }
            scenario.onActivity { assertTrue(model(it).query.value.constraints.people.isEmpty()); it.findViewById<View>(R.id.options_back).performClick() }
            val places = hold(scenario, R.id.search_filters)
            places.send(MotionEvent.ACTION_MOVE, atY = places.y + places.row * 2)
            scenario.onActivity { assertEquals(it.getString(R.string.search_hold_places), it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString()) }
            SystemClock.sleep(250); assertEquals(0, placesCalls.get())
            screenshot("filters-hold-places.png")
            places.send(MotionEvent.ACTION_UP, atY = places.y + places.row * 2)
            await(scenario, "Places requires inline location choices") { it.findViewById<View>(R.id.places_panel).isVisible && placesCalls.get() == 3 && !ViewModelProvider(it)[SearchOptionsViewModel::class.java].state.value.placesLoading }
            scenario.onActivity { assertNull(model(it).query.value.constraints.country) }
        }
    }

    @Test fun filterHoldResetIsExplicitAndPreservesSearchTextModeAndEmojiTag() {
        randomTags.set(true)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            readySearch(scenario, "happy 😂")
            scenario.onActivity {
                model(it).filter("😂")
                model(it).searchConstraints(SearchConstraints(profile.identity, listOf(SearchPerson(alice, "Alice", profile.identity)),
                    country = "France", media = SearchMedia.VIDEOS))
            }
            await(scenario, "Selected filter set must load") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value }
            val before = requests.size
            val cancelled = hold(scenario, R.id.search_filters)
            cancelled.send(MotionEvent.ACTION_MOVE, atY = cancelled.y + cancelled.row * 3)
            scenario.onActivity { assertEquals(it.getString(R.string.search_reset), it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString()) }
            cancelled.send(MotionEvent.ACTION_CANCEL)
            SystemClock.sleep(250); assertEquals(before, requests.size)
            scenario.onActivity { assertEquals(3, model(it).query.value.constraints.count) }
            val reset = hold(scenario, R.id.search_filters)
            reset.send(MotionEvent.ACTION_UP, atY = reset.y + reset.row * 3)
            await(scenario, "Reset requires an explicit release") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).query.value.constraints.count == 0 }
            scenario.onActivity {
                assertEquals("happy 😂", model(it).query.value.text); assertEquals("😂", model(it).query.value.filter)
                assertEquals(SearchMode.CONTEXT, model(it).query.value.mode)
                assertFalse(it.findViewById<View>(R.id.search_options_panel).isVisible)
            }
            assertEquals(before + 1, requests.size)
        }
    }

    @Test fun sourceHoldLoopsNamedProfilesAndSwitchesOnlyOnRelease() {
        val second = SourceProfile(UUID.randomUUID().toString(), "Second library", (profile.configuration as ImmichConfig).copy(apiKey = "second-search-fixture-key"))
        runBlocking { access.sources().upsert(second) }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Named sources must load") { !model(it).state.value.loading && ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value.catalog.profiles.size == 2 }
            val before = requests.size; val copied = clip()
            val drag = hold(scenario, R.id.source_toggle)
            drag.send(MotionEvent.ACTION_MOVE, atY = drag.y - drag.row * 4)
            scenario.onActivity {
                assertEquals("Second library", it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString())
                assertEquals(profile.id, ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value.catalog.selectedId)
                assertFalse(it.findViewById<View>(R.id.source_panel).isVisible)
            }
            SystemClock.sleep(250); assertEquals(before, requests.size)
            screenshot("sources-hold-preview.png")
            drag.send(MotionEvent.ACTION_UP, atY = drag.y - drag.row * 4)
            await(scenario, "Release must activate the selected source") { !model(it).state.value.loading && model(it).state.value.sourceId == second.identity && ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value.catalog.selectedId == second.id }
            assertEquals(before + 1, requests.size); assertEquals(copied, clip())
            val local = hold(scenario, R.id.source_toggle)
            local.send(MotionEvent.ACTION_UP, atY = local.y + local.row * 2)
            await(scenario, "Samples must remain in the loop") { !model(it).state.value.loading && model(it).state.value.sourceId == "local" && model(it).state.value.items.size == 7 }
            assertEquals(before + 1, requests.size)
            scenario.onActivity { assertEquals(2, ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value.catalog.profiles.size) }
        }
    }

    @Test fun sourceHoldCancelsOnRenameAndKeepsAddSourceInTheFullPicker() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Source must load") { !model(it).state.value.loading && ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value.loaded }
            val before = requests.size
            val cancelled = hold(scenario, R.id.source_toggle)
            cancelled.send(MotionEvent.ACTION_MOVE, atY = cancelled.y - cancelled.row)
            scenario.onActivity { assertEquals(it.getString(R.string.picker_source_samples), it.findViewById<View>(R.id.hold_choice_card).contentDescription.toString()) }
            cancelled.send(MotionEvent.ACTION_CANCEL)
            val rename = hold(scenario, R.id.source_toggle)
            rename.send(MotionEvent.ACTION_MOVE, atY = rename.y + rename.row)
            runBlocking { access.sources().upsert(profile.copy(name = "Renamed library")) }
            await(scenario, "Editing the source list must invalidate the preview") { it.findViewById<View>(R.id.hold_choice_card) == null && ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value.catalog.selected!!.name == "Renamed library" }
            rename.send(MotionEvent.ACTION_UP)
            SystemClock.sleep(650); assertEquals(before, requests.size)
            val tap = ChoiceDrag(scenario, R.id.source_toggle); SystemClock.sleep(125); tap.send(MotionEvent.ACTION_UP)
            await(scenario, "Short taps must retain the full source picker") { it.findViewById<View>(R.id.source_panel).isVisible }
            val monitor = instrumentation.addMonitor(ImmichSettingsActivity::class.java.name, null, false)
            try {
                scenario.onActivity { it.findViewById<View>(R.id.source_configure).performClick() }
                val editor = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
                assertNotNull("Add source needs the setup screen", editor)
                instrumentation.runOnMainSync { assertTrue(editor.findViewById<EditText>(R.id.api_key).text.isEmpty()); editor.finish() }
                assertEquals(before, requests.size)
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun modesUseOneMetadataFieldAndRetainBothPaginationContracts() = runBlocking {
        val source = access.mediaSource()
        val criteria = SearchConstraints(profile.identity, listOf(SearchPerson(alice, "Alice", profile.identity)),
            "France", "Île-de-France", "Paris", SearchMedia.VIDEOS)
        for ((mode, field) in listOf(SearchMode.FILENAME to "originalFileName", SearchMode.DESCRIPTION to "description", SearchMode.PATH to "originalPath", SearchMode.OCR to "ocr")) {
            source.page(MediaQuery("happy 😂", mode = mode, constraints = criteria))
            val (path, body) = requests.last(); assertEquals("/api/search/metadata", path)
            val filter = body.getJSONObject("filter")
            for (candidate in listOf("originalFileName", "description", "originalPath", "ocr")) assertEquals(candidate == field, filter.has(candidate))
            assertEquals(if (mode == SearchMode.OCR) "happy 😂" else "%happy 😂%", filter.getJSONObject(field).getString(if (mode == SearchMode.OCR) "matches" else "like"))
            assertEquals(alice, filter.getJSONObject("personIds").getJSONArray("all").getString(0))
            assertEquals("France", filter.getJSONObject("country").getString("eq"))
            assertEquals("VIDEO", filter.getJSONObject("type").getJSONArray("in").getString(0))
            assertFalse(body.has("query")); assertFalse(body.has("page"))
        }
        val contextQuery = MediaQuery("paging 😂", constraints = criteria)
        val first = source.page(contextQuery); assertNotNull(first.nextCursor)
        source.page(contextQuery, first.nextCursor)
        val (path, body) = requests.last(); assertEquals("/api/search/smart", path)
        assertEquals("paging 😂", body.getString("query")); assertEquals(2, body.getInt("page")); assertFalse(body.has("filter"))
        assertEquals("VIDEO", body.getString("type")); assertEquals("Paris", body.getString("city"))
        assertEquals(alice, body.getJSONArray("personIds").getString(0))
        val pathQuery = MediaQuery("paging", mode = SearchMode.PATH)
        val metadata = source.page(pathQuery); assertEquals("opaque-second", metadata.nextCursor)
        source.page(pathQuery, metadata.nextCursor)
        assertEquals("opaque-second", requests.last().second.getString("cursor"))
        val other = SourceProfile(UUID.randomUUID().toString(), "Second fixture", profile.configuration)
        access.sources().upsert(other, true)
        source.page(contextQuery)
        assertFalse(requests.last().second.has("personIds")); assertFalse(requests.last().second.has("country"))
        assertEquals("VIDEO", requests.last().second.getString("type"))
    }

    @Test fun inlineModesPeoplePlacesAndMediaSurviveReopenAndRecreationWithoutRecopy() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.discord", null)).use { scenario ->
            await(scenario, "Initial Context browsing must load") { !model(it).state.value.loading && model(it).state.value.items.isNotEmpty() }
            scenario.onActivity { activity ->
                val view = activity.findViewById<RecyclerView>(R.id.media_grid)
                val holder = view.findViewHolderForAdapterPosition(0)!!
                holder.itemView.performClick()
            }
            await(scenario, "Selection must copy") { model(it).copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback) }
            val copied = clip(); assertNotNull(copied)
            scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick(); it.findViewById<EditText>(R.id.search).setText("happy 😂") }
            await(scenario, "Search controls must be exposed") { it.findViewById<View>(R.id.search_controls).isVisible }
            scenario.onActivity { it.findViewById<View>(R.id.query_mode).performClick() }
            await(scenario, "Type selector must be inline") { it.findViewById<View>(R.id.type_panel).isVisible }
            SystemClock.sleep(5_000) // Let Android's clipboard overlay dismiss before UI evidence.
            screenshot("search-type-options.png")
            scenario.onActivity { it.findViewById<View>(R.id.mode_filename).performClick() }
            await(scenario, "Filename mode must search") { model(it).query.value.mode == SearchMode.FILENAME && !model(it).state.value.loading }
            scenario.onActivity { it.findViewById<View>(R.id.search_filters).performClick() }
            await(scenario, "People choices must load") { it.findViewById<RecyclerView>(R.id.people_list).adapter!!.itemCount == 2 }
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.people_list).findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
            await(scenario, "Person must be selected") { model(it).query.value.constraints.people.size == 1 }
            screenshot("search-people-options.png")
            scenario.onActivity { it.findViewById<View>(R.id.section_places).performClick() }
            await(scenario, "Places must load") { !ViewModelProvider(it)[SearchOptionsViewModel::class.java].state.value.placesLoading }
            onView(withId(R.id.country)).perform(click()); onData(equalTo("France")).inRoot(isPlatformPopup()).perform(click())
            await(scenario, "Country must be selected") { model(it).query.value.constraints.country == "France" }
            screenshot("search-place-options.png")
            scenario.onActivity { it.findViewById<View>(R.id.section_media).performClick(); it.findViewById<View>(R.id.media_videos).performClick() }
            await(scenario, "Videos filter must load") { model(it).query.value.constraints.media == SearchMedia.VIDEOS && !model(it).state.value.loading }
            assertEquals(copied, clip())
            scenario.onActivity { it.findViewById<View>(R.id.options_back).performClick() }
            scenario.recreate()
            await(scenario, "Choices must survive recreation") { model(it).query.value.let { q -> q.mode == SearchMode.FILENAME && q.constraints.people.singleOrNull()?.id == alice && q.constraints.country == "France" && q.constraints.media == SearchMedia.VIDEOS } }
            assertEquals(copied, clip())
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Choices must survive closing and reopening") { model(it).query.value.mode == SearchMode.FILENAME && model(it).query.value.constraints.people.singleOrNull()?.id == alice }
            scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
            await(scenario, "Reopened controls must be visible") { it.findViewById<View>(R.id.search_controls).isVisible }
            screenshot("search-expanded.png")
        }
    }

    @Test fun failedPeopleLookupNeedsAnExplicitRetry() {
        rejectPeople.set(true)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Source must load") { !model(it).state.value.loading }
            scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
            await(scenario, "Controls must be visible") { it.findViewById<View>(R.id.search_controls).isVisible }
            scenario.onActivity { it.findViewById<View>(R.id.search_filters).performClick() }
            await(scenario, "Permission error must be inline") { ViewModelProvider(it)[SearchOptionsViewModel::class.java].state.value.peopleError }
            assertEquals(1, peopleCalls.get())
            scenario.onActivity { it.findViewById<View>(R.id.section_places).performClick(); it.findViewById<View>(R.id.section_people).performClick() }
            SystemClock.sleep(500); assertEquals(1, peopleCalls.get())
            rejectPeople.set(false)
            scenario.onActivity { it.findViewById<View>(R.id.people_more).performClick() }
            await(scenario, "Explicit retry must recover") { it.findViewById<RecyclerView>(R.id.people_list).adapter!!.itemCount == 2 }
            assertEquals(2, peopleCalls.get())
        }
    }

    @Test fun luckyUsesTheServerRandomArrayAndRetainsOnlyApplicableFilters() = runBlocking {
        randomTags.set(true)
        val album = "a1ff3b80-344f-48f0-8a15-c2dd47f6ff2a"
        profile = profile.copy(configuration = (profile.configuration as ImmichConfig).copy(albumId = album))
        access.sources().upsert(profile, true)
        val criteria = SearchConstraints(profile.identity, listOf(SearchPerson(alice, "Alice", profile.identity)),
            "France", "Île-de-France", "Paris", SearchMedia.VIDEOS)
        val query = MediaQuery("ignore this text 😂", filter = "😂", mode = SearchMode.OCR, constraints = criteria, randomRequest = "request-one")
        val source = access.mediaSource()
        val page = source.page(query)
        assertEquals(100, page.items.size); assertEquals(100, page.items.map { it.id }.distinct().size)
        assertTrue(page.items.all { it.isVideo }); assertNull(page.nextCursor)
        val (path, body) = requests.last(); assertEquals("/api/search/random", path); assertEquals(100, body.getInt("size"))
        for (field in listOf("query", "page", "cursor", "orderBy", "randomRequest")) assertFalse(field, body.has(field))
        val filter = body.getJSONObject("filter")
        for (field in listOf("originalFileName", "description", "originalPath", "ocr")) assertFalse(field, filter.has(field))
        assertEquals(album, filter.getJSONObject("albumIds").getJSONArray("any").getString(0))
        assertEquals("cd59f6b0-ff74-4d47-bcb6-d02e9d3b4a34", filter.getJSONObject("tagIds").getJSONArray("any").getString(0))
        assertEquals(alice, filter.getJSONObject("personIds").getJSONArray("all").getString(0))
        assertEquals("France", filter.getJSONObject("country").getString("eq"))
        assertTrue(filter.getJSONObject("trashedAt").isNull("eq"))
        assertEquals(1, randomCalls.get())
        assertTrue(source.page(query, "not-a-random-cursor").items.isEmpty()); assertEquals(1, randomCalls.get())
        val other = SourceProfile(UUID.randomUUID().toString(), "Second random fixture", profile.configuration)
        access.sources().upsert(other, true); source.page(query)
        val scoped = requests.last().second.getJSONObject("filter")
        assertFalse(scoped.has("personIds")); assertFalse(scoped.has("country")); assertFalse(scoped.has("state")); assertFalse(scoped.has("city"))
        assertEquals("VIDEO", scoped.getJSONObject("type").getJSONArray("in").getString(0))
    }

    @Test fun luckyReshufflesOnDemandAndRestoresTheBatchAndFocusWithoutRecopy() {
        var firstIds = emptyList<String>()
        var focused: String? = null
        var nonce: String? = null
        var copied: String? = null
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.discord", null)).use { scenario ->
            await(scenario, "Source must load") { !model(it).state.value.loading && model(it).state.value.items.size == 4 }
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.media_grid).findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
            await(scenario, "Initial selection must copy") { model(it).copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback) }
            copied = clip(); assertNotNull(copied)
            scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick(); it.findViewById<EditText>(R.id.search).setText("happy 😂") }
            await(scenario, "Search must finish before Lucky") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).query.value.text == "happy 😂" }
            scenario.onActivity {
                it.findViewById<View>(R.id.search_lucky).performClick()
                model(it).lucky() // Same frame's second tap must not start another request.
            }
            await(scenario, "A full random batch must load") { !model(it).state.value.loading && model(it).state.value.items.size == 100 && model(it).query.value.randomRequest != null }
            assertEquals(1, randomCalls.get()); assertEquals(copied, clip())
            scenario.onActivity {
                assertNull(model(it).selection.value); assertEquals("", model(it).query.value.text)
                nonce = model(it).query.value.randomRequest; firstIds = model(it).state.value.items.map { item -> item.id }
                it.findViewById<RecyclerView>(R.id.media_grid).scrollToPosition(40)
            }
            await(scenario, "The random carousel must navigate") { model(it).focusedId?.let { id -> firstIds.indexOf(id) >= 30 } == true }
            SystemClock.sleep(5_000); screenshot("search-lucky-results.png")
            await(scenario, "The random carousel must settle before recording focus") { it.findViewById<RecyclerView>(R.id.media_grid).scrollState == RecyclerView.SCROLL_STATE_IDLE }
            scenario.onActivity { focused = model(it).focusedId }
            scenario.recreate()
            var restored = ""
            try {
                await(scenario, "Recreation must retain the random batch") {
                    val current = model(it)
                    restored = "loaded=${!current.state.value.loading},sameItems=${current.state.value.items.map { item -> item.id } == firstIds},sameRequest=${current.query.value.randomRequest == nonce},expectedFocus=$focused,focus=${current.focusedId},calls=${randomCalls.get()}"
                    !current.state.value.loading && current.state.value.items.map { item -> item.id } == firstIds && current.focusedId == focused
                }
            } catch (failure: AssertionError) { throw AssertionError("${failure.message}: $restored", failure) }
            assertEquals(1, randomCalls.get()); assertEquals(copied, clip())
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Reopening must retain the batch and focus") { !model(it).state.value.loading && model(it).query.value.randomRequest == nonce && model(it).focusedId == focused }
            assertEquals(1, randomCalls.get())
            scenario.onActivity { model(it).loadMore(99); it.findViewById<View>(R.id.search_toggle).performClick() }
            await(scenario, "Shuffle again must be available") { it.findViewById<View>(R.id.search_lucky).isEnabled }
            screenshot("search-lucky-controls.png")
            scenario.onActivity { it.findViewById<View>(R.id.search_lucky).performClick() }
            await(scenario, "Another tap must fetch a fresh batch") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).state.value.items.size == 100 && model(it).query.value.randomRequest != nonce }
            assertEquals(2, randomCalls.get())
            scenario.onActivity {
                assertTrue(model(it).state.value.items.none { item -> item.id in firstIds })
                it.findViewById<View>(R.id.search_toggle).performClick()
            }
            await(scenario, "Random mode control must appear") { it.findViewById<View>(R.id.query_mode).isVisible }
            scenario.onActivity { it.findViewById<View>(R.id.query_mode).performClick(); it.findViewById<View>(R.id.mode_context).performClick() }
            await(scenario, "Selecting the already checked Context mode must exit random browsing") { !model(it).state.value.loading && model(it).query.value.randomRequest == null && model(it).state.value.items.size == 4 }
            scenario.onActivity { model(it).lucky() }
            await(scenario, "A new Lucky request must load") { !model(it).state.value.loading && model(it).state.value.query == model(it).query.value && model(it).state.value.items.size == 100 && model(it).query.value.randomRequest != null }
            assertEquals(3, randomCalls.get())
            scenario.onActivity { it.findViewById<EditText>(R.id.search).setText("happy 😂") }
            await(scenario, "Typing must resume default Context search") { !model(it).state.value.loading && model(it).state.value.query.text == "happy 😂" && model(it).state.value.items.size == 4 }
            scenario.onActivity { assertNull(model(it).query.value.randomRequest); assertEquals(SearchMode.CONTEXT, model(it).query.value.mode) }
            assertEquals("/api/search/smart", requests.last().first); assertEquals("happy 😂", requests.last().second.getString("query"))
            assertEquals(copied, clip())
        }
    }

    @Test fun luckyAuthenticationFailureStopsUntilExplicitRetryAndSamplesShuffleLocally() {
        rejectRandom.set(true)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Source must load") { !model(it).state.value.loading }
            scenario.onActivity { model(it).lucky() }
            await(scenario, "Random failure must surface inline") { model(it).state.value.error && model(it).query.value.randomRequest != null }
            SystemClock.sleep(800); assertEquals(1, randomCalls.get())
            rejectRandom.set(false)
            scenario.onActivity { it.findViewById<View>(R.id.message).performClick() }
            await(scenario, "Explicit retry must recover") { !model(it).state.value.loading && !model(it).state.value.error && model(it).state.value.items.size == 100 }
            assertEquals(2, randomCalls.get())
        }
        runBlocking { access.sources().select(null); access.browseSession().reset() }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Samples must load") { !model(it).state.value.loading && model(it).state.value.items.size == 7 }
            scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
            await(scenario, "Samples must expose Lucky without Immich filters") { it.findViewById<View>(R.id.search_lucky).isVisible && !it.findViewById<View>(R.id.query_mode).isVisible && !it.findViewById<View>(R.id.search_filters).isVisible }
            scenario.onActivity { it.findViewById<View>(R.id.search_lucky).performClick() }
            await(scenario, "Local shuffled batch must load") { !model(it).state.value.loading && model(it).query.value.randomRequest != null && model(it).state.value.items.size == 7 }
            scenario.onActivity { assertEquals(7, model(it).state.value.items.map { item -> item.id }.distinct().size) }
            assertEquals(2, randomCalls.get())
        }
    }

    @Test fun searchOptionsRemainReachableAtLargeTextInLandscapeAndRestoreBrowsing() {
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }
        val oldScale = shell("settings get system font_scale")
        try {
            shell("settings put system font_scale 2.0"); shell("wm user-rotation lock 1")
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await(scenario, "Landscape source must load") { !model(it).state.value.loading && it.windowManager.maximumWindowMetrics.bounds.let { bounds -> bounds.width() > bounds.height() } }
                scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
                await(scenario, "Search must expand") { it.findViewById<View>(R.id.search_field).isVisible }
                scenario.onActivity {
                    if (it.findViewById<View>(R.id.search_controls).isVisible) it.findViewById<View>(R.id.query_mode).performClick()
                    else it.findViewById<View>(R.id.search_toggle).performClick()
                }
                await(scenario, "Options must fit with the keyboard dismissed") {
                    it.findViewById<View>(R.id.type_panel).isVisible && it.findViewById<View>(R.id.options_header).isVisible &&
                        it.findViewById<View>(R.id.picker_panel).height > 400
                }
                screenshot("search-options-large-landscape.png")
                onView(withId(R.id.mode_ocr)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                await(scenario, "OCR selection must restore the results area") { model(it).query.value.mode == SearchMode.OCR && it.findViewById<View>(R.id.media_grid).isVisible }
                scenario.onActivity { it.findViewById<View>(R.id.close).performClick() }
            }
        } finally {
            if (oldScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $oldScale")
            shell("wm user-rotation lock 0")
        }
    }
}
