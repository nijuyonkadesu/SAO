package one.njk.sao.floating

import one.njk.sao.source.*
import one.njk.sao.immichprovider.*

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import one.njk.sao.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in read-only comparison with Immich web's smart-search request across images and videos. */
class LiveEmojiSearchTest {
    @Test fun pickerMatchesWebsiteSmartSearchForEmojiAndMixedText(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val input = File(app.filesDir, "live_immich.json")
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveImmich") == "true" && input.isFile)
        val credentials = JSONObject(input.readText())
        val config = ImmichConfig(credentials.getString("url"), credentials.getString("key"), smartSearch = true)
        val access = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
        val previous = access.sources().current()
        val report = JSONObject().put("routing", "search/smart").put("server_writes", false)
        val comparisons = JSONArray()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        fun websiteResult(query: String): List<String> {
            // Same fields used by web's default contextual search across images and videos.
            val json = JSONObject().put("query", query).put("visibility", "timeline")
                .put("language", "en").put("page", 1).put("size", 100).put("withExif", true)
            val request = Request.Builder().url(config.connection.apiUrl.resolve("search/smart")!!)
                .header("x-api-key", config.apiKey).post(json.toString().toRequestBody("application/json".toMediaType())).build()
            return http.newCall(request).execute().use { response ->
                assertEquals("Website request must succeed", 200, response.code)
                val items = JSONObject(response.body!!.string()).getJSONObject("assets").getJSONArray("items")
                (0 until items.length()).map { items.getJSONObject(it).getString("id") }
            }
        }
        fun model(activity: PickerActivity) = ViewModelProvider(activity)[PickerViewModel::class.java]
        try {
            access.sources().restore(SourceCatalog())
            val firstProfile = immichProfile("Personal",config)
            access.sources().upsert(firstProfile, select = true); access.browseSession().reset()
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
                for (query in listOf("skill", "GIF", "😂", "😭", "💀", "👩🏽‍💻", "happy 😂")) {
                    val expected = websiteResult(query)
                    assertTrue("Live smart search must return media", expected.isNotEmpty())
                    scenario.onActivity { it.findViewById<EditText>(R.id.search).setText(query) }
                    var actual: List<String>? = null
                    val deadline = SystemClock.elapsedRealtime() + 30_000
                    while (actual == null && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            val state = model(activity).state.value
                            if (state.query.text == query && !state.loading && !state.error)
                                actual = state.items.map { it.remote!!.assetId }
                        }
                        SystemClock.sleep(30)
                    }
                    assertNotNull("Picker search must finish", actual)
                    assertTrue("Picker must preserve the website's ranked media results", expected == actual)
                    if (query in listOf("skill", "GIF")) {
                        scenario.onActivity { activity ->
                            assertTrue("Live context rank zero must include MP4", model(activity).state.value.items.first().isVideo)
                            activity.findViewById<View>(R.id.search_toggle).performClick()
                        }
                        var playing = false; val playbackDeadline = SystemClock.elapsedRealtime() + 30_000
                        while (!playing && SystemClock.elapsedRealtime() < playbackDeadline) {
                            scenario.onActivity { activity ->
                                val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                                val holder = grid.findViewHolderForAdapterPosition(0) as? MediaCarouselAdapter.Holder
                                playing = holder?.binding?.videoPreview?.let { it.alpha == 1f && it.player?.isPlaying == true } == true
                            }
                            SystemClock.sleep(40)
                        }
                        assertTrue("Live MP4 must render in the floating carousel", playing)
                        scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
                    }
                    comparisons.put(JSONObject().put("query", query).put("website_count", expected.size)
                        .put("picker_count", actual!!.size).put("same_ranked_media", true))
                }
            }
            // Add a second same-kind connection through the real form, with smart search's default.
            ExtensionForm().use { form ->
                form.set("source_name","Reactions"); form.set("server_url",config.serverUrl); form.set("api_key",config.apiKey)
                form.click("save_connection")
                withTimeout(30_000) { while(access.sources().current().profiles.size != 2) delay(40) }
            }
            val second = access.sources().current().selected!!
            assertTrue(second.configuration is ExtensionConfig)
            assertNotEquals(firstProfile.identity, second.identity)
            val expected = websiteResult("skill")
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                assertEquals("Reopening after source switch preserves query", "happy 😂", access.browseSession().snapshot().query.text)
                scenario.onActivity { it.findViewById<View>(R.id.source_toggle).performClick() }
                if (InstrumentationRegistry.getArguments().getString("pauseForVisual") == "true") {
                    File(app.filesDir, "live_visual_ready").writeText("ready")
                    delay(15_000)
                    File(app.filesDir, "live_visual_ready").delete()
                }
                scenario.onActivity {
                    it.findViewById<View>(R.id.source_back).performClick()
                    it.findViewById<EditText>(R.id.search).setText("skill")
                }
                var ready = false
                withTimeout(30_000) { while (!ready) {
                    scenario.onActivity {
                        val state = model(it).state.value
                        ready = state.query.text == "skill" && !state.loading && !state.error && state.items.firstOrNull()?.remote?.sourceId == second.identity
                    }; delay(40)
                } }
                scenario.onActivity {
                    assertTrue("Second named source must preserve website ranking", expected == model(it).state.value.items.map { item -> item.remote!!.assetId })
                    assertTrue(model(it).state.value.items.first().isVideo)
                }
            }
            report.put("queries", comparisons).put("named_profiles", 2).put("same_kind_switch_parity", true).put("success", true)
        } finally {
            File(app.filesDir, "live_emoji_report.json").writeText(report.toString(2))
            input.delete(); access.sources().restore(previous); access.browseSession().reset()
        }
    }
}
