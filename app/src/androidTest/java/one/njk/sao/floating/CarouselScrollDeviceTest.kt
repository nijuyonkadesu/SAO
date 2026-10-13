package one.njk.sao.floating

import android.graphics.drawable.Animatable
import android.os.SystemClock
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import one.njk.sao.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class CarouselScrollDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private lateinit var server: MockWebServer
    private var previous = SourceCatalog()
    private var mediaKind = "GIF"
    private fun grid(activity: PickerActivity) = activity.findViewById<RecyclerView>(R.id.media_grid)
    private fun holders(view: RecyclerView) = view.children.mapNotNull {
        view.getChildViewHolder(it) as? MediaCarouselAdapter.Holder
    }.toList()

    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.browseSession().reset()
        val poster = app.assets.open("floating/happy.png").use { it.readBytes() }
        val gif = app.assets.open("floating/hello.gif").use { it.readBytes() }
        val video = instrumentation.context.assets.open("preview-video.webm").use { it.readBytes() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/tags" -> MockResponse().setBody("[]")
                    request.path?.contains("/thumbnail") == true -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(poster))
                    request.path?.contains("/original") == true -> MockResponse().setHeader("Content-Type", if (mediaKind == "GIF") "image/gif" else "video/webm")
                        .setBody(Buffer().write(if (mediaKind == "GIF") gif else video))
                    else -> {
                        val items = JSONArray()
                        for (index in 0 until 100) items.put(JSONObject()
                            .put("id", UUID(42, index.toLong()).toString())
                            .put("type", if (mediaKind == "GIF") "IMAGE" else "VIDEO")
                            .put("originalMimeType", if (mediaKind == "GIF") "image/gif" else "video/webm")
                            .put("originalFileName", "reaction-$index.${if (mediaKind == "GIF") "gif" else "webm"}")
                            .put("updatedAt", "2026-10-11T00:00:00Z"))
                        MockResponse().setBody(JSONObject().put("assets", JSONObject().put("items", items)).toString())
                    }
                }
            }
            start()
        }
        access.sources().upsert(SourceProfile(UUID.randomUUID().toString(), "Scroll fixture",
            ImmichConfig(server.url("/").toString(), "scroll-fixture-key")), true)
    }

    @After fun cleanup() = runBlocking {
        access.sources().restore(previous); access.browseSession().reset(); server.shutdown()
    }

    private fun await(scenario: ActivityScenario<PickerActivity>, label: String, predicate: (PickerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = predicate(it) }; SystemClock.sleep(40)
        }
        assertTrue(label, ready)
    }

    @Test fun changingFocalGifDoesNotRequestNewBadgeLayouts() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.discord", null)).use { scenario ->
            await(scenario, "GIF holders must be measured and loaded") { activity ->
                val view = grid(activity); val cards = holders(view)
                (view.adapter as MediaCarouselAdapter).currentList.size == 100 && cards.size >= 2 &&
                    cards.all { it.item?.animated == true && it.binding.preview.drawable != null &&
                        it.binding.gifBadge.width > 0 && !it.binding.gifBadge.isLayoutRequested }
            }
            scenario.onActivity { activity ->
                val view = grid(activity); val adapter = view.adapter as MediaCarouselAdapter
                val cards = holders(view)
                val next = cards.first { it.item?.id != adapter.focusedId }.item!!
                val before = cards.count { it.binding.gifBadge.isLayoutRequested }
                adapter.focusedId = next.id
                val after = cards.count { it.binding.gifBadge.isLayoutRequested }
                val report = JSONObject()
                    .put("attached_cards", cards.size).put("badge_layout_requests_before", before)
                    .put("badge_layout_requests_after", after).toString()
                File(app.filesDir, "carousel-focus-layout.json").writeText(report)
                android.util.Log.i("SaoCarouselScroll", report)
                assertEquals("Changing playback focus must not remeasure unchanged badge text", 0, after)
            }
        }
    }

    @Test fun fastFlingSuspendsMotionPlaybackUntilTheCarouselSettles() {
        mediaKind = "VIDEO"
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.discord", null)).use { scenario ->
            await(scenario, "Initial video must render") { activity ->
                holders(grid(activity)).any { it.binding.videoPreview.let { view -> view.alpha == 1f && view.player?.isPlaying == true } }
            }
            var movingFrames = 0
            var movingFramesWithMotion = 0
            var settlingTransitionsWithPlayer = 0
            scenario.onActivity { activity ->
                val view = grid(activity)
                view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState != RecyclerView.SCROLL_STATE_IDLE && holders(recyclerView).any { it.binding.videoPreview.player != null })
                            settlingTransitionsWithPlayer++
                    }
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                            movingFrames++
                            if (holders(recyclerView).any { it.binding.videoPreview.player != null ||
                                    (it.binding.preview.drawable as? Animatable)?.isRunning == true }) movingFramesWithMotion++
                        }
                    }
                })
                assertTrue(view.fling(minOf(10_000, view.maxFlingVelocity), 0))
            }
            await(scenario, "A hard fling must traverse multiple cards and resume only at rest") { activity ->
                val view = grid(activity)
                val adapter = view.adapter as MediaCarouselAdapter
                movingFrames > 5 && view.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    adapter.currentList.indexOfFirst { it.id == adapter.focusedId } > 1 &&
                    holders(view).any { it.binding.videoPreview.player?.isPlaying == true }
            }
            scenario.onActivity {
                val report = JSONObject()
                    .put("moving_scroll_callbacks", movingFrames).put("moving_callbacks_with_motion", movingFramesWithMotion)
                    .put("settling_transitions_with_player", settlingTransitionsWithPlayer).toString()
                File(app.filesDir, "carousel-fling-motion.json").writeText(report)
                android.util.Log.i("SaoCarouselScroll", report)
                // RecyclerView dispatches listeners in reverse registration order. The state
                // callback may precede SAO's callback; actual moving frames must have no player.
                assertEquals("Moving cards must use their static preview", 0, movingFramesWithMotion)
            }
        }
    }
}
