package one.njk.sao.floating

import one.njk.sao.source.*
import one.njk.sao.immichprovider.*

import android.content.ClipboardManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import one.njk.sao.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** All assets and responses are local fixtures. Never touches a configured personal library. */
class SequenceBrowseDeviceTest {
    @get:org.junit.Rule val marquees = PickerMarqueeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private val preferences = FloatingPreferences(app)
    private lateinit var previous: SourceCatalog
    private lateinit var oldOptions: FloatingOptions
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val rejectTimeline = AtomicBoolean()
    private val slowTimeline = AtomicBoolean()
    private val updatedAsset = AtomicBoolean()
    private val omitHeldAsset = AtomicBoolean()
    private var numberedPreviews = false
    private var searchIndices = listOf(80, 5)
    private val album = "22222222-4222-4222-8222-222222222222"
    private fun id(index: Int) = UUID(0x1111111141114111L, Long.MIN_VALUE + index).toString()
    private fun asset(index: Int) = JSONObject().put("id", id(index)).put("type", "IMAGE")
        .put("originalFileName", "sequence-$index.png").put("originalMimeType", "image/png")
        .put("updatedAt", if (updatedAsset.get()) "2026-10-16T00:00:00Z" else "2026-10-15T00:00:00Z").put("visibility", "timeline")
        .put("fileCreatedAt", if (index < 60) "2026-10-10T09:00:00Z" else "2026-09-10T09:00:00Z")
        .put("localDateTime", if (index < 60) "2026-10-10T09:00:00" else "2026-09-10T09:00:00")
        // Deliberately unrelated to timeline ordering: this is the caption's date, not its sort key.
        .put("createdAt", "2026-10-15T12:${(119-index).rem(60).toString().padStart(2, '0')}:00Z")
    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.sources().restore(SourceCatalog()); oldOptions = preferences.current()
        access.browseSession().reset(); preferences.browseSequence(true); preferences.longPressMs(160)
        val png = InstrumentationRegistry.getInstrumentation().context.assets.open("floating/happy.png").use { it.readBytes() }
        val numbered = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/api/server/features") return MockResponse().setBody("""{"search":true,"smartSearch":true,"ocr":true}""")
                    if (path == "/api/tags") return MockResponse().setBody("[]")
                    if (path.endsWith("/thumbnail") || path.endsWith("/original")) {
                        val assetId = path.substringAfter("/api/assets/").substringBefore('/')
                        val image = if (!numberedPreviews) png else numbered.computeIfAbsent(assetId) {
                            val index = (0..119).first { id(it) == assetId }
                            val bitmap = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888)
                            android.graphics.Canvas(bitmap).apply {
                                drawColor(android.graphics.Color.HSVToColor(floatArrayOf((index * 137.5f) % 360, .4f, .85f)))
                                drawText("Image $index", 120f, 130f, android.graphics.Paint().apply {
                                    textSize = 36f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
                                })
                            }
                            java.io.ByteArrayOutputStream().use { output ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                                bitmap.recycle(); output.toByteArray()
                            }
                        }
                        return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image))
                    }
                    if (path == "/api/timeline/buckets") {
                        if (rejectTimeline.get()) return MockResponse().setResponseCode(403)
                        return MockResponse().setBody("""[{"timeBucket":"2026-10-01","count":60},{"timeBucket":"2026-09-01","count":60}]""")
                    }
                    if (path == "/api/timeline/bucket") {
                        val start = if (request.requestUrl!!.queryParameter("timeBucket")!!.startsWith("2026-10")) 0 else 60
                        val body = JSONObject().put("id", JSONArray((start until start+60).map(::id)))
                            .put("future", JSONObject().put("ignored", true)).toString()
                        return MockResponse().setBody(body).also { if (slowTimeline.get()) it.throttleBody(8, 3, TimeUnit.SECONDS) }
                    }
                    if (path.startsWith("/api/assets/")) {
                        val index = (0..119).first { id(it) == path.substringAfterLast('/') }
                        return MockResponse().setBody(asset(index).toString())
                    }
                    if (path == "/api/search/smart") return results(searchIndices)
                    if (path == "/api/search/metadata") {
                        val filter = JSONObject(request.body.clone().readUtf8()).getJSONObject("filter")
                        val branches = filter.optJSONArray("or")
                        val indices = branches?.let { values -> (0 until values.length()).map { i ->
                            val wanted = values.getJSONObject(i).getJSONObject("id").getString("eq")
                            (0..119).first { id(it) == wanted }
                        } } ?: listOf(80, 5)
                        // Hydration is intentionally returned in a different order from the timeline.
                        return results(indices.reversed().filterNot { omitHeldAsset.get() && it == 80 })
                    }
                    return MockResponse().setResponseCode(404)
                }
                private fun results(indices: List<Int>) = MockResponse().setBody(JSONObject().put("assets",
                    JSONObject().put("items", JSONArray(indices.map(::asset)))).toString())
            }
            start()
        }
        access.sources().upsert(immichProfile("Sequence fixture",
            ImmichConfig(server.url("/").toString(), "local-sequence-fixture", albumId = album)), true)
    }
    @After fun cleanup() = runBlocking {
        access.sources().restore(previous); access.browseSession().reset()
        preferences.browseSequence(oldOptions.browseSequence); preferences.longPressMs(oldOptions.longPressMs)
        server.shutdown()
    }
    private fun model(activity: PickerActivity) = ViewModelProvider(activity)[PickerViewModel::class.java]
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue(label, condition())
    }
    private fun ready(scenario: ActivityScenario<PickerActivity>): PickerViewModel {
        lateinit var picker: PickerViewModel
        scenario.onActivity { picker = model(it); picker.search("sequence") }
        await("Search must load") { !picker.state.value.loading && picker.state.value.query.text == "sequence" && picker.state.value.items.size == searchIndices.size && picker.canBrowseSequence.value && picker.options.value.browseSequence }
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
        return picker
    }
    private fun clipboard(): String? {
        var value: String? = null
        instrumentation.runOnMainSync { value = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri?.toString() }
        return value
    }
    private inner class Touch(private val activity: PickerActivity, view: View, localX: Float? = null) {
        private val down = SystemClock.uptimeMillis()
        private var x = 0f; private var y = 0f
        init { instrumentation.runOnMainSync {
            val point = IntArray(2); view.getLocationOnScreen(point)
            x = point[0] + (localX ?: view.width / 2f); y = point[1] + view.height / 2f
        }; send(MotionEvent.ACTION_DOWN) }
        fun send(action: Int, deltaX: Float = 0f) = instrumentation.runOnMainSync {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + deltaX, y, 0)
            activity.dispatchTouchEvent(event); event.recycle()
        }
    }
    private fun first(scenario: ActivityScenario<PickerActivity>): Pair<PickerActivity, View> {
        lateinit var activity: PickerActivity; lateinit var view: View
        scenario.onActivity { activity = it; view = grid(it).findViewHolderForAdapterPosition(0)!!.itemView }
        return activity to view
    }
    private fun grid(activity: PickerActivity): RecyclerView {
        val sequence = activity.findViewById<RecyclerView>(R.id.sequence_grid)
        return if (sequence.isVisible) sequence else activity.findViewById(R.id.media_grid)
    }
    private fun focal(grid: RecyclerView): MediaCarouselAdapter.Holder? {
        if (!grid.isShown) return null
        val view = com.google.android.material.carousel.CarouselSnapHelper(false).findSnapView(grid.layoutManager!!) ?: return null
        return grid.getChildViewHolder(view) as MediaCarouselAdapter.Holder
    }
    @Test fun closingAfterCopyResumesTheInnerScreenAndBackRestoresTheOriginalSearch() {
        searchIndices = (40..119).toList() + (0..19).toList()
        lateinit var originalModel: PickerViewModel
        lateinit var innerItems: List<MediaItem>
        var sequenceId: String? = null
        var copied: String? = null
        var readsBeforeDismiss = 0
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            originalModel = ready(scenario)
            scenario.onActivity { grid(it).scrollToPosition(47) }
            await("Search must settle on the original result") { originalModel.focusedId == originalModel.state.value.items[47].identityKey }
            scenario.onActivity { originalModel.browseSequence(originalModel.state.value.items.first { item -> item.remote!!.assetId == id(80) }) }
            await("Initial sequence must load") { originalModel.state.value.sequenceId != null && !originalModel.state.value.loading }
            scenario.onActivity { originalModel.loadMore(0) }
            await("Sequence must retain pages beyond its initial window") { originalModel.state.value.items.size == 81 && !originalModel.state.value.loadingMore }
            scenario.onActivity { grid(it).scrollToPosition(35) }
            await("Inner carousel must visibly navigate") {
                var correct = false
                scenario.onActivity { correct = focal(grid(it))?.item?.remote?.assetId == id(55) }; correct
            }
            scenario.onActivity {
                assertNull("Sequence Copy must need no image tap", originalModel.selection.value)
                assertTrue(it.findViewById<View>(R.id.copy_action).isEnabled)
                it.findViewById<View>(R.id.copy_action).performClick()
            }
            await("The chosen photo must copy") { originalModel.copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback) }
            copied = clipboard(); innerItems = originalModel.state.value.items; sequenceId = originalModel.currentSequenceId
            readsBeforeDismiss = requests.count { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") || it.requestUrl!!.encodedPath.startsWith("/api/search/") }
            // All successful Paste/share paths finish this activity; dismiss through that same lifecycle.
            scenario.onActivity {
                it.findViewById<View>(R.id.close).performClick()
                originalModel.loadMore(0) // A late layout callback during the exit animation.
            }
        }
        val reads = requests.count { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") || it.requestUrl!!.encodedPath.startsWith("/api/search/") }
        assertEquals("Exit-animation callbacks must not restart paging", readsBeforeDismiss, reads)
        val prefs = java.io.File(app.filesDir, "datastore/floating_picker.preferences_pb")
        val bytes = prefs.readBytes(); val modified = prefs.lastModified()
        SystemClock.sleep(350)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
            lateinit var picker: PickerViewModel
            reopened.onActivity { picker = model(it); assertNotSame(originalModel, picker) }
            await("A new picker instance must resume the exact loaded sequence") {
                picker.currentSequenceId == sequenceId && picker.state.value.sequenceId == sequenceId && !picker.state.value.loading
            }
            assertSame("Keep the loaded sequence and its pagination state", innerItems, picker.state.value.items)
            await("Resume the actual visible inner photo") {
                var correct = false
                reopened.onActivity { correct = focal(grid(it))?.item?.remote?.assetId == id(55) }; correct
            }
            reopened.onActivity {
                originalModel.focus(MediaIdentity("stale-owner","stale-exiting-picker"), sequenceId)
                originalModel.pickerVisible(false)
                originalModel.rememberBrowsing()
                assertFalse("An exiting picker cannot clear the new picker's navigation", originalModel.backToResults())
            }
            assertEquals(copied, clipboard())
            assertEquals("Reopening must not refetch search or timeline metadata", reads,
                requests.count { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") || it.requestUrl!!.encodedPath.startsWith("/api/search/") })
            assertArrayEquals("No preference writes for sequence restoration", bytes, prefs.readBytes())
            assertEquals(modified, prefs.lastModified())
            reopened.onActivity { picker.loadMore(0) }
            await("The retained source-owned cursors must still page") { picker.state.value.items.size == 101 && !picker.state.value.loadingMore }
            reopened.onActivity { it.findViewById<View>(R.id.sequence_back).performClick() }
            await("Back must reveal the original search position") {
                var correct = false
                reopened.onActivity { correct = focal(grid(it))?.item?.remote?.assetId == id(87) }; correct
            }
            assertEquals("sequence", picker.query.value.text)
            reopened.onActivity {
                assertTrue("Restored search center must be actionable", it.findViewById<View>(R.id.copy_action).isEnabled)
                it.findViewById<View>(R.id.copy_action).performClick()
            }
            await("Back must copy the restored search center, not the sequence selection") {
                picker.selection.value?.remote?.assetId == id(87) && clipboard() != copied
            }
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
            await("Explicit Back must remain at the search screen on the next opening") {
                var correct = false
                reopened.onActivity { correct = model(it).state.value.let { state -> !state.loading && state.sequenceId == null && state.query.text == "sequence" } }; correct
            }
        }
    }
    @Test fun openingSearchKeepsTheInnerScreenUntilANewQueryIsSubmitted() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            scenario.onActivity { picker.browseSequence(picker.state.value.items.first()) }
            await("Sequence must load") { picker.currentSequenceId != null && !picker.state.value.loading }
            val sequence = picker.currentSequenceId
            scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
            assertEquals("Merely opening the search bar must retain the inner screen", sequence, picker.currentSequenceId)
            scenario.onActivity { it.findViewById<EditText>(R.id.search).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) }
            await("Explicitly submitting even the same query must return to search results") { picker.currentSequenceId == null && picker.state.value.sequenceId == null }
            scenario.onActivity { picker.browseSequence(picker.state.value.items.first()) }
            await("A new sequence must open") { picker.currentSequenceId != null && !picker.state.value.loading }
            scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
            scenario.onActivity { it.findViewById<EditText>(R.id.search).setText("another query") }
            await("A new query must leave the sequence") { picker.state.value.sequenceId == null && !picker.state.value.loading && picker.state.value.query.text == "another query" }
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
            await("The new search must replace the parked inner screen") {
                var correct = false
                reopened.onActivity { correct = model(it).state.value.let { state -> !state.loading && state.sequenceId == null && state.query.text == "another query" } }; correct
            }
        }
    }
    @Test fun closingDuringInitialReadCancelsWorkUntilThePickerIsOpenedAgain() {
        slowTimeline.set(true)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            scenario.onActivity { picker.browseSequence(picker.state.value.items.first()) }
            await("The initial streaming read must begin") { requests.any { it.requestUrl!!.encodedPath == "/api/timeline/bucket" } }
            scenario.onActivity { it.finish() }
        }
        val reads = requests.count { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") }
        SystemClock.sleep(1_100)
        assertEquals("No reads or retries while the picker is closed", reads, requests.count { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") })
        slowTimeline.set(false)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
            try {
            await("Opening the picker must resume the interrupted inner screen") {
                var correct = false
                reopened.onActivity { correct = model(it).state.value.let { state -> state.sequenceId != null && !state.loading && !state.error } && focal(grid(it))?.item?.remote?.assetId == id(80) }; correct
            }
            } catch(error: AssertionError) {
                var details=""
                reopened.onActivity { val value=model(it).state.value
                    details="sequence=${value.sequenceId}, loading=${value.loading}, error=${value.error}/${value.errorMessage?.let { message -> app.getString(message) }}, count=${value.items.size}, focal=${focal(grid(it))?.item?.remote?.assetId}, reads=${requests.map { request -> request.requestUrl!!.encodedPath }.takeLast(10)}"
                }
                pauseSourceVisual("resume",details)
                throw AssertionError(details,error)
            }
        }
    }
    @Test fun changingSourcesWhileClosedDiscardsTheOldInnerScreen() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            scenario.onActivity { picker.browseSequence(picker.state.value.items.first()) }
            await("Sequence must load") { picker.currentSequenceId != null && !picker.state.value.loading }
        }
        runBlocking { access.sources().select(null) }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { reopened ->
            await("A different source must not restore another source's sequence") {
                var correct = false
                reopened.onActivity { correct = model(it).state.value.let { state -> state.sourceId == "local" && !state.loading && state.sequenceId == null } }; correct
            }
        }
    }
    @Test fun heldImageIsActuallyVisibleAndBackKeepsTheScrolledSearchView() {
        searchIndices = (40..119).toList() + (0..19).toList()
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            lateinit var searchGrid: RecyclerView
            lateinit var searchAdapter: RecyclerView.Adapter<*>
            lateinit var searchManager: RecyclerView.LayoutManager
            var searchListChanges = 0
            scenario.onActivity {
                searchGrid = it.findViewById(R.id.media_grid)
                searchAdapter = searchGrid.adapter!!; searchManager = searchGrid.layoutManager!!
                searchAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { searchListChanges++ }
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { searchListChanges++ }
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { searchListChanges++ }
                    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { searchListChanges++ }
                })
                searchGrid.scrollToPosition(47)
            }
            await("Search must visibly reach result 47") {
                var visible = false
                scenario.onActivity { visible = !searchGrid.isLayoutRequested && focal(searchGrid)?.item?.remote?.assetId == id(87) }
                visible
            }
            lateinit var card: View
            lateinit var activity: PickerActivity
            var originalLeft = 0
            scenario.onActivity {
                activity = it; card = focal(searchGrid)!!.itemView; originalLeft = card.left
            }
            val hold = Touch(activity, card)
            await("Hold must finish loading") { picker.state.value.sequenceId != null && !picker.state.value.loading }
            hold.send(MotionEvent.ACTION_UP)
            await("The held asset must be the visible focal card, not just the model's focus") {
                var visible = false
                scenario.onActivity {
                    val grid = grid(it)
                    visible = !grid.isLayoutRequested && focal(grid)?.item?.remote?.assetId == id(87)
                }; visible
            }
            scenario.onActivity { grid(it).scrollToPosition(32) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("Back must visibly return to the original result") {
                var restored = false
                scenario.onActivity { restored = !searchGrid.isLayoutRequested && focal(searchGrid)?.item?.remote?.assetId == id(87) }
                picker.state.value.sequenceId == null && restored
            }
            scenario.onActivity {
                assertSame("Keep the original search adapter", searchAdapter, searchGrid.adapter)
                assertSame("Keep the original layout manager", searchManager, searchGrid.layoutManager)
                assertEquals("Restore its exact settled offset", originalLeft, focal(searchGrid)!!.itemView.left)
                assertEquals("Sequence navigation must not replace or reset the search list", 0, searchListChanges)
            }
        }
    }
    @Test fun paginationDuringFirstDiffAndUpdatedMetadataKeepTheExactHeldAssetVisible() {
        searchIndices = (40..119).toList() + (0..19).toList()
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            val anchor = picker.state.value.items.first { it.remote!!.assetId == id(80) }
            updatedAsset.set(true)
            scenario.onActivity { activity ->
                activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    picker.state.first { it.sequenceId != null && !it.loading }
                    // A pagination emission can supersede the initial asynchronous list diff's callback.
                    picker.loadMore(0)
                }
                picker.browseSequence(anchor)
            }
            await("The next month must prepend during the initial list update") {
                picker.state.value.sequenceId != null && picker.state.value.items.size == 81 && !picker.state.value.loadingMore
            }
            await("The actual visible card must remain the held asset despite changed revision IDs and prepending") {
                var correct = false
                scenario.onActivity { correct = !grid(it).isLayoutRequested && focal(grid(it))?.item?.remote?.assetId == id(80) }
                correct
            }
            assertNotEquals("The test must exercise changed media revision IDs", anchor.id,
                picker.state.value.items.first { it.remote!!.assetId == id(80) }.id)
        }
    }
    @Test fun holdingTheVisibleCardEdgeDoesNotSelectAnOverlappingMaskedNeighbor() {
        numberedPreviews = true
        searchIndices = (40..119).toList() + (0..19).toList()
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            scenario.onActivity { grid(it).scrollToPosition(47) }
            await("Search must reach the expected visible image") {
                var correct = false
                scenario.onActivity { correct = focal(grid(it))?.item?.remote?.assetId == id(87) }; correct
            }
            lateinit var activity: PickerActivity
            lateinit var card: com.google.android.material.carousel.MaskableFrameLayout
            var edge = 0f
            scenario.onActivity {
                activity = it; card = focal(grid(it))!!.itemView as com.google.android.material.carousel.MaskableFrameLayout
                edge = card.maskRectF.right - 20 * it.resources.displayMetrics.density
            }
            screenshot("sequence-edge-before.png")
            val hold = Touch(activity, card, edge)
            await("Hold must open the sequence") { picker.state.value.sequenceId != null && !picker.state.value.loading }
            hold.send(MotionEvent.ACTION_UP)
            assertEquals("A visible edge press must browse the photo under the finger, not its masked neighbor",
                id(87), picker.state.value.items.firstOrNull { it.identityKey == picker.focusedId }?.remote?.assetId)
            await("The photo touched on its visible edge must actually be centered") {
                var correct = false
                scenario.onActivity { correct = focal(grid(it))?.item?.remote?.assetId == id(87) }; correct
            }
            screenshot("sequence-edge-after.png")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("Back must show the original card") {
                var correct = false
                scenario.onActivity { correct = focal(grid(it))?.item?.remote?.assetId == id(87) }; correct
            }
            val tap = Touch(activity, card, edge)
            tap.send(MotionEvent.ACTION_UP)
            await("A short edge tap must still select and copy the visible photo") {
                picker.selection.value?.remote?.assetId == id(87) &&
                    picker.copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback)
            }
        }
    }
    @Test fun missingHeldAssetShowsAnErrorWithoutSubstitutingAnUnrelatedPhoto() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            val anchor = picker.state.value.items.first()
            omitHeldAsset.set(true)
            scenario.onActivity { picker.browseSequence(anchor) }
            await("A missing anchor must be recoverable") { picker.state.value.sequenceId != null && picker.state.value.error }
            assertEquals(listOf(anchor), picker.state.value.items)
            await("Keep showing the held photo with the error") {
                var correct = false
                scenario.onActivity { correct = focal(grid(it))?.item?.remote?.assetId == id(80) }; correct
            }
        }
    }
    @Test fun longHoldBrowsesBothDirectionsAndBackRestoresSearchWithoutRecopy() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            val (activity, card) = first(scenario)
            instrumentation.runOnMainSync { card.performClick() }
            await("Tap must copy") { picker.copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback) }
            val copied = clipboard(); val original = picker.state.value.items
            val focus = picker.focusedId; val searches = requests.count { it.requestUrl!!.encodedPath == "/api/search/smart" }
            val touch = Touch(activity, card)
            SystemClock.sleep(80); assertNull(picker.state.value.sequenceId)
            await("Hold must load chronological neighbors") { picker.state.value.sequenceId != null && !picker.state.value.loading && picker.state.value.items.size == 41 }
            touch.send(MotionEvent.ACTION_UP)
            assertEquals((60..100).map(::id), picker.state.value.items.map { it.remote!!.assetId })
            assertEquals(copied, clipboard())
            instrumentation.runOnMainSync { picker.loadMore(0) }
            await("Newer month must prepend") { picker.state.value.items.size == 81 && !picker.state.value.loadingMore }
            assertEquals((20..100).map(::id), picker.state.value.items.map { it.remote!!.assetId })
            instrumentation.runOnMainSync { picker.loadMore(80) }
            await("Older items must append") { picker.state.value.items.size == 100 && !picker.state.value.loadingMore }
            instrumentation.runOnMainSync { picker.loadMore(0) }
            await("Timeline must reach its beginning") { picker.state.value.items.size == 120 && !picker.state.value.loadingMore }
            assertEquals((0..119).map(::id), picker.state.value.items.map { it.remote!!.assetId })
            scenario.recreate()
            scenario.onActivity { assertNotNull(model(it).state.value.sequenceId) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("Back must restore search") { picker.state.value.sequenceId == null && picker.state.value.items == original }
            assertEquals("sequence", picker.query.value.text); assertEquals(focus, picker.focusedId)
            assertEquals(searches, requests.count { it.requestUrl!!.encodedPath == "/api/search/smart" })
            assertEquals(copied, clipboard())
            val timeline = requests.filter { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") }
            assertTrue(timeline.isNotEmpty())
            for (request in timeline) {
                assertEquals("takenAt", request.requestUrl!!.queryParameter("orderBy"))
                assertEquals("desc", request.requestUrl!!.queryParameter("order"))
                assertEquals("true", request.requestUrl!!.queryParameter("withStacked"))
                assertEquals("Sequence must retain the source's album scope", album, request.requestUrl!!.queryParameter("albumId"))
            }
            val hydration = requests.filter { it.requestUrl!!.encodedPath == "/api/search/metadata" &&
                JSONObject(it.body.clone().readUtf8()).getJSONObject("filter").has("or") }
            assertTrue(hydration.isNotEmpty())
            for (request in hydration) assertEquals(album, JSONObject(request.body.clone().readUtf8())
                .getJSONObject("filter").getJSONObject("albumIds").getJSONArray("any").getString(0))
        }
    }
    @Test fun holdingInsideSequenceDoesNothingAndTapStillCopies() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            val anchor = picker.state.value.items.first()
            scenario.onActivity { picker.browseSequence(anchor) }
            await("Sequence must settle around its anchor") {
                var ready = false
                scenario.onActivity { ready = picker.state.value.sequenceId != null && !picker.state.value.loading &&
                    !picker.state.value.loadingMore && focal(grid(it))?.item?.id == anchor.id }
                ready
            }
            val before = picker.state.value
            val focus = picker.focusedId
            var beforeClip = 0L
            scenario.onActivity { beforeClip = it.getSystemService(ClipboardManager::class.java).primaryClipDescription?.timestamp ?: 0 }
            val reads = requests.count { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") || it.requestUrl!!.encodedPath.startsWith("/api/search/") }
            lateinit var activity: PickerActivity
            lateinit var card: View
            lateinit var originalGrid: RecyclerView
            scenario.onActivity { activity = it; originalGrid = grid(it); card = focal(originalGrid)!!.itemView }
            val touch = Touch(activity, card)
            SystemClock.sleep(300); touch.send(MotionEvent.ACTION_UP)
            scenario.onActivity {
                card.performLongClick() // Accessibility/native long-click must also be a no-op.
                picker.browseSequence(before.items.first { item -> item.id != anchor.id })
            }
            SystemClock.sleep(250)
            assertEquals(before.sequenceId, picker.state.value.sequenceId)
            assertSame(before.items, picker.state.value.items)
            assertEquals(focus, picker.focusedId)
            assertEquals(reads, requests.count { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") || it.requestUrl!!.encodedPath.startsWith("/api/search/") })
            scenario.onActivity {
                assertSame(originalGrid, grid(it))
                assertEquals(anchor.id, focal(grid(it))?.item?.id)
                assertEquals(beforeClip, it.getSystemService(ClipboardManager::class.java).primaryClipDescription?.timestamp ?: 0)
                card.performClick()
            }
            await("Ordinary taps in the sequence still copy") { picker.selection.value?.id == anchor.id &&
                picker.copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback) }
        }
    }
    @Test fun sharedDelayControlsResultsAndAllThreeSelectors() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            runBlocking { preferences.longPressMs(500) }
            await("Custom hold delay must load") { picker.options.value.longPressMs == 500 }
            val (activity, card) = first(scenario)
            val touch = Touch(activity, card)
            SystemClock.sleep(220); assertNull(picker.state.value.sequenceId)
            touch.send(MotionEvent.ACTION_UP)
            await("Shorter tap must still copy") { picker.copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback) }
            val customHold = Touch(activity, first(scenario).second)
            await("Longer hold must open the sequence") { picker.state.value.sequenceId != null && !picker.state.value.loading }
            customHold.send(MotionEvent.ACTION_UP)
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            await("Search must return") { picker.state.value.sequenceId == null }
            for (button in listOf(R.id.query_mode, R.id.search_filters, R.id.source_toggle)) {
                scenario.onActivity {
                    if (button != R.id.source_toggle) it.findViewById<View>(R.id.search_toggle).let { toggle ->
                        if (!it.findViewById<View>(R.id.search_field).isVisible) toggle.performClick()
                    }
                }
                if (button == R.id.source_toggle) scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                var target: View? = null
                scenario.onActivity { target = it.findViewById(button) }
                await("Selector must be visible") { target!!.isShown }
                SystemClock.sleep(350)
                val hold = Touch(activity, target!!)
                SystemClock.sleep(220)
                var expanded = false
                instrumentation.runOnMainSync { expanded = activity.findViewById<View>(R.id.hold_choice_card) != null }
                assertFalse("Selector must respect 500ms", expanded)
                await("Selector must expand after custom delay") {
                    instrumentation.runOnMainSync { expanded = activity.findViewById<View>(R.id.hold_choice_card) != null }; expanded
                }
                hold.send(MotionEvent.ACTION_CANCEL)
            }
        }
    }
    @Test fun returningFromADifferentAnchorRestoresTheOriginalFocalItem() {
        searchIndices = (40..119).toList() + (0..19).toList()
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            val original = picker.state.value.items
            scenario.onActivity { grid(it).scrollToPosition(47) }
            await("Original carousel must navigate") { picker.focusedId == original[47].identityKey }
            scenario.onActivity { picker.browseSequence(original[0]) }
            await("Other result must open as sequence anchor") { picker.state.value.sequenceId != null && !picker.state.value.loading }
            scenario.onActivity { grid(it).scrollToPosition(30) }
            await("Sequence must navigate independently") { picker.focusedId == picker.state.value.items[30].identityKey }
            scenario.onActivity { it.findViewById<View>(R.id.sequence_back).performClick() }
            await("Original results and focal item must restore") {
                picker.state.value.sequenceId == null && picker.state.value.items == original && picker.focusedId == original[47].identityKey
            }
        }
    }
    @Test fun failureNeedsExplicitRetryAndBackCancelsStalledReads() {
        rejectTimeline.set(true)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            scenario.onActivity { picker.browseSequence(picker.state.value.items.first()) }
            await("Permission failure must remain recoverable") { picker.state.value.sequenceId != null && picker.state.value.error }
            val attempts = requests.count { it.requestUrl!!.encodedPath == "/api/timeline/buckets" }
            SystemClock.sleep(1_100)
            assertEquals(attempts, requests.count { it.requestUrl!!.encodedPath == "/api/timeline/buckets" })
            assertEquals(1, attempts)
            rejectTimeline.set(false); slowTimeline.set(true)
            scenario.onActivity { picker.retry() }
            await("Streaming timeline must start") { requests.any { it.requestUrl!!.encodedPath == "/api/timeline/bucket" } }
            lateinit var activity: PickerActivity
            // ActivityScenario waits for rendering idle; measure the actual Back callback instead.
            instrumentation.runOnMainSync { activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<PickerActivity>().single() }
            var started = 0L
            instrumentation.runOnMainSync { started = SystemClock.elapsedRealtime(); activity.onBackPressedDispatcher.onBackPressed() }
            await("Back must promptly restore search") { picker.state.value.sequenceId == null && !picker.state.value.loading && picker.state.value.items.size == 2 }
            val elapsed = SystemClock.elapsedRealtime() - started
            android.util.Log.i("SAOSequenceTest", "back_to_results_ms=$elapsed")
            assertTrue("Back took ${elapsed}ms", elapsed < 1_000)
            val reads = requests.count { it.requestUrl!!.encodedPath == "/api/timeline/bucket" }
            SystemClock.sleep(1_100)
            assertEquals(reads, requests.count { it.requestUrl!!.encodedPath == "/api/timeline/bucket" })
        }
    }
    @Test fun sourceSwitchDropsOldTimelineAndUploadCaptionsAreOnlyInSequence() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            scenario.onActivity {
                val card = grid(it).findViewHolderForAdapterPosition(0)!!.itemView
                assertFalse(card.findViewById<View>(R.id.uploaded_at).isVisible)
                picker.browseSequence(picker.state.value.items.first())
            }
            await("Sequence must render") { picker.state.value.sequenceId != null && !picker.state.value.loading }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350)
            scenario.onActivity {
                val grid = grid(it)
                val holder = grid.findViewHolderForAdapterPosition(picker.state.value.items.indexOfFirst { item -> item.remote!!.assetId == id(80) })!!
                val caption = holder.itemView.findViewById<TextView>(R.id.uploaded_at)
                assertTrue(caption.isVisible); assertFalse(caption.text.toString().contains("Uploaded"))
                assertTrue(caption.text.toString().contains("2026"))
            }
            screenshot("sequence-neighbors.png")
            runBlocking { access.sources().select(null) }
            await("Source switch must discard the timeline") { picker.state.value.sourceId == "local" && picker.state.value.sequenceId == null }
            assertFalse(picker.backToResults())
            assertTrue(picker.state.value.items.none { it.remote != null })
        }
    }
    @Test fun scrollingCancelsHoldAndDisabledOptionNeverReadsTimeline() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            val picker = ready(scenario)
            val (activity, card) = first(scenario)
            val before = clipboard()
            val swipe = Touch(activity, card)
            SystemClock.sleep(30); swipe.send(MotionEvent.ACTION_MOVE, -200f)
            SystemClock.sleep(250); swipe.send(MotionEvent.ACTION_UP, -200f)
            assertNull(picker.state.value.sequenceId); assertEquals(before, clipboard())
            runBlocking { preferences.browseSequence(false) }
            await("Setting must disable holds") { !picker.options.value.browseSequence }
            scenario.onActivity {
                val grid = grid(it)
                val holder = grid.findViewHolderForAdapterPosition(0) ?: grid.findViewHolderForAdapterPosition(1)!!
                assertFalse(holder.itemView.performLongClick())
                holder.itemView.performClick()
            }
            await("Disabled mode must retain tap copy") { picker.copyStatus.value in setOf(R.string.picker_copied, R.string.picker_media_fallback) }
            assertTrue(requests.none { it.requestUrl!!.encodedPath.startsWith("/api/timeline/") })
        }
    }
    @Test fun inlineSettingsSaveDelayAfterReleaseAndSurviveRecreation() {
        androidx.test.core.app.ActivityScenario.launch(one.njk.sao.SettingsActivity::class.java).use { scenario ->
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.browse_sequence))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click())
            await("Inline toggle must save") { !runBlocking { preferences.current().browseSequence } }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.long_press_delay))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo())
            lateinit var activity: one.njk.sao.SettingsActivity
            lateinit var slider: com.google.android.material.slider.Slider
            var x = 0f; var y = 0f
            scenario.onActivity {
                activity = it; slider = it.findViewById(R.id.long_press_delay)
                val point = IntArray(2); slider.getLocationOnScreen(point)
                x = point[0] + slider.width * .6f; y = point[1] + slider.height / 2f
            }
            val down = SystemClock.uptimeMillis()
            fun send(action: Int) = instrumentation.runOnMainSync {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                activity.dispatchTouchEvent(event); event.recycle()
            }
            send(MotionEvent.ACTION_DOWN)
            SystemClock.sleep(150)
            assertEquals("Dragging must not write each intermediate value", 160, runBlocking { preferences.current().longPressMs })
            send(MotionEvent.ACTION_UP)
            await("Releasing must save delay") { runBlocking { preferences.current().longPressMs } != 160 }
            val saved = runBlocking { preferences.current().longPressMs }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(saved.toFloat(), it.findViewById<com.google.android.material.slider.Slider>(R.id.long_press_delay).value)
                assertFalse(it.findViewById<com.google.android.material.chip.Chip>(R.id.browse_sequence).isChecked)
            }
            runBlocking { preferences.browseSequence(true); preferences.longPressMs(160) }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.browse_sequence))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo())
            screenshot("sequence-settings.png")
        }
    }
    @Test fun sequenceBackAndCopyStayReachableWithLargeTextInLandscape() {
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
        }
        val oldScale = shell("settings get system font_scale")
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                val picker = ready(scenario)
                scenario.onActivity { picker.browseSequence(picker.state.value.items.first()) }
                await("Sequence must load") { picker.state.value.sequenceId != null && !picker.state.value.loading }
                shell("settings put system font_scale 2.0"); shell("wm user-rotation lock 1")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("Large-text landscape must render") {
                    var done = false; scenario.onActivity { done = it.resources.configuration.let { c -> c.fontScale >= 1.9f && c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE } }; done
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(400)
                scenario.onActivity { activity ->
                    val panel = activity.findViewById<View>(R.id.picker_panel)
                    val bounds = android.graphics.Rect(); assertTrue(panel.getGlobalVisibleRect(bounds))
                    for (id in listOf(R.id.sequence_back, R.id.copy_action, R.id.sequence_grid)) {
                        val view = activity.findViewById<View>(id)
                        val visible = android.graphics.Rect()
                        assertTrue("Control must remain visible: $id", view.getGlobalVisibleRect(visible))
                        assertTrue("Control must remain within the picker: $id", bounds.contains(visible))
                        assertEquals("Control must not be clipped: $id", view.height, visible.height())
                    }
                }
                screenshot("sequence-large-landscape.png")
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.sequence_back))
                    .perform(androidx.test.espresso.action.ViewActions.click())
                await("Back must restore results in landscape") { picker.state.value.sequenceId == null && picker.state.value.items.size == 2 }
            }
        } finally { shell("settings put system font_scale $oldScale"); shell("wm user-rotation lock 0") }
    }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        if (InstrumentationRegistry.getArguments().getString("saoScreenshots") != "true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(app.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
