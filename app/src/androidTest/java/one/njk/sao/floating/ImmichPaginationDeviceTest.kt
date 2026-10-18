package one.njk.sao.floating

import one.njk.sao.source.*
import one.njk.sao.immichprovider.*

import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import one.njk.sao.R
import okio.Buffer

class ImmichPaginationDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private lateinit var server: MockWebServer
    private var previous = SourceCatalog()
    private lateinit var source: SwitchingMediaSource
    private val pages = mutableListOf<Int>()
    private fun asset(index: Int, type: String = "IMAGE", visibility: String = "timeline") = JSONObject()
        .put("id", UUID(0, index.toLong()).toString()).put("type", type).put("visibility", visibility)
        .put("originalFileName", "reaction-$index.png").put("originalMimeType", "image/png").put("updatedAt", "2026-10-10T00:00:00Z")
    private fun response(items: List<JSONObject>, next: String? = null) = JSONObject().put("assets", JSONObject()
        .put("items", JSONArray(items)).put("nextPage", next ?: JSONObject.NULL)).toString()
    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.sources().restore(SourceCatalog()); access.browseSession().reset()
        server = MockWebServer(); server.start()
        access.sources().upsert(immichProfile("Paged", ImmichConfig(server.url("/").toString(), "pagination-contract-key")), true)
        source = SwitchingMediaSource(app, LocalMediaSource(app), access.immichSettings(), FloatingDispatchers())
    }
    @After fun cleanup() = runBlocking {
        source.shutdown(); access.sources().restore(previous); access.browseSession().reset(); server.shutdown()
    }
    @Test fun contextSearchFollowsServerPagesPastOneThousandWithoutChangingUnicodeOrRanking(): Unit = runBlocking {
        val total = 1205
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path == "/api/server/features") return MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                if (request.path == "/api/tags") return MockResponse().setBody("[]")
                val body = JSONObject(request.body.readUtf8()); val page = body.getInt("page"); pages += page
                assertEquals("skill 😂", body.getString("query")); assertEquals(100, body.getInt("size"))
                assertFalse(body.has("filter")); assertFalse(body.has("cursor")); assertFalse(body.getBoolean("withDeleted"))
                val start = (page - 1) * 100 + 1; val end = minOf(total, page * 100)
                return MockResponse().setBody(response((start..end).map(::asset), if (end < total) "${page + 1}" else null))
            }
        }
        val items = mutableListOf<MediaItem>(); var cursor: String? = null
        do {
            val result = source.page(MediaQuery("skill 😂"), cursor); items += result.items; cursor = result.nextCursor
        } while (cursor != null)
        assertEquals(total, items.size)
        assertEquals((1..total).map { UUID(0, it.toLong()).toString() }, items.map { it.remote!!.assetId })
        assertEquals((1..13).toList(), pages); assertEquals(14, server.requestCount)
    }
    @Test fun filteredOnlyPagesAdvanceAndDuplicateEmojiTagNamesKeepAnySemanticsAndContextRank(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"smartSearch":true,"ocr":true}"""))
        val tags="""[{"id":"tag-one","name":"😂","value":"one/😂"},{"id":"tag-two","name":"😂","value":"two/😂"}]"""
        server.enqueue(MockResponse().setBody(tags))
        server.enqueue(MockResponse().setBody(response(listOf(asset(1, visibility = "hidden")), "2")))
        server.enqueue(MockResponse().setBody(tags))
        server.enqueue(MockResponse().setBody(response(listOf(asset(2, "AUDIO")), "3")))
        server.enqueue(MockResponse().setBody(tags))
        server.enqueue(MockResponse().setBody(response(listOf(asset(3), asset(4), asset(5, visibility = "archive")), "4")))
        // Metadata returns IDs in a different order; the smart-search ranking must win.
        server.enqueue(MockResponse().setBody(response(listOf(asset(5), asset(3)))))
        val result = source.page(MediaQuery("happy 😂", filter = "😂"))
        assertEquals(listOf(UUID(0, 3).toString(), UUID(0, 5).toString()), result.items.map { it.remote!!.assetId })
        assertEquals("immich-smart-page:4", result.nextCursor)
        assertEquals("/api/server/features",server.takeRequest().path)
        for (page in 1..3) {
            assertEquals("/api/tags",server.takeRequest().path)
            val request = server.takeRequest(); assertEquals("/api/search/smart", request.path)
            val body = JSONObject(request.body.readUtf8()); assertEquals(page, body.getInt("page")); assertFalse(body.has("tagIds"))
            assertEquals("happy 😂", body.getString("query"))
        }
        val lookup = JSONObject(server.takeRequest().body.readUtf8()); val filter = lookup.getJSONObject("filter")
        assertEquals(2, filter.getJSONObject("tagIds").getJSONArray("any").length())
        assertEquals(3, filter.getJSONArray("or").length()); assertEquals(3, lookup.getInt("size")); assertFalse(lookup.has("query"))
    }
    @Test fun invalidOrEmptyContinuationsFailOnceWithoutRequestLoops(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"smartSearch":true,"ocr":true}"""))
        for (body in listOf(response(listOf(asset(1)), "1"), response(emptyList(), "2"))) {
            server.enqueue(MockResponse().setBody(body))
            try { source.page(MediaQuery("GIF")); fail("Invalid continuation accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
            catch (_: SourceReadException) { }
        }
        assertEquals(3, server.requestCount)
    }
    private fun model(activity: PickerActivity) = ViewModelProvider(activity)[PickerViewModel::class.java]
    private fun await(scenario: ActivityScenario<PickerActivity>, label: String, predicate: (PickerActivity) -> Boolean) {
        var ready = false; val deadline = SystemClock.elapsedRealtime() + 12_000
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = predicate(it) }; SystemClock.sleep(40)
        }
        assertTrue(label, ready)
    }
    @Test fun carouselBindingPrefetchesRetriesSamePageAndKeepsLoadedResultsAcrossReopening(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val sample = LocalMediaSource(app).all().first { !it.animated }
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open(sample.asset).use { it.readBytes() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path == "/api/server/features") return MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                if (request.path == "/api/tags") return MockResponse().setBody("[]")
                if (request.path?.contains("/thumbnail") == true) return MockResponse().setBody(Buffer().write(image)).setHeader("Content-Type", "image/png")
                if (request.path == "/api/search/metadata") return MockResponse().setBody(response((1..100).map(::asset)))
                val body = JSONObject(request.body.readUtf8()); val page = body.getInt("page")
                synchronized(pages) { pages += page }
                if (page == 2 && attempts.incrementAndGet() <= 2) return MockResponse().setResponseCode(503)
                val start = (page - 1) * 100 + 1
                return MockResponse().setBody(response((start until start + 100).map(::asset), if (page < 3) "${page + 1}" else null))
            }
        }
        var focus: String? = null
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            scenario.onActivity { it.findViewById<EditText>(R.id.search).setText("skill") }
            await(scenario, "Initial context page must load") { model(it).state.value.let { state -> state.query.text == "skill" && !state.loading && state.items.size == 100 } }
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.media_grid).scrollToPosition(94) }
            await(scenario, "Next-page failure must preserve the existing page") { model(it).state.value.let { state -> state.error && !state.loadingMore && state.items.size == 100 } }
            assertEquals(listOf(1, 2, 2), synchronized(pages) { pages.toList() })
            SystemClock.sleep(600); assertEquals(3, synchronized(pages) { pages.size })
            scenario.onActivity { it.findViewById<TextView>(R.id.message).performClick() }
            await(scenario, "Explicit retry must resume the same continuation") { model(it).state.value.let { state -> !state.error && state.items.size == 200 } }
            scenario.onActivity { focus = model(it).focusedId; assertNotNull(focus) }
        }
        val requests = synchronized(pages) { pages.toList() }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            await(scenario, "Loaded pages and search must survive reopening") { model(it).state.value.let { state -> state.query.text == "skill" && state.items.size == 200 } }
            assertEquals(requests, synchronized(pages) { pages.toList() })
            scenario.onActivity { assertEquals(focus, model(it).focusedId); it.findViewById<RecyclerView>(R.id.media_grid).scrollToPosition(194) }
            await(scenario, "Scrolling must fetch the terminal page") { model(it).state.value.let { state -> state.items.size == 300 && !state.hasMore } }
            assertEquals(listOf(1, 2, 2, 2, 3), synchronized(pages) { pages.toList() })
        }
    }
}
