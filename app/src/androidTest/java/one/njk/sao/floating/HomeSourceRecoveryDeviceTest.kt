package one.njk.sao.floating

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import one.njk.sao.MainActivity
import one.njk.sao.R
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import one.njk.sao.viewmodels.ArtsViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Local synthetic server only; verifies the real profile-aware cross-APK settings route. */
class HomeSourceRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val profiles get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java).sources()
    private val online = AtomicBoolean()
    private val searches = AtomicInteger()
    private fun server(): MockWebServer {
        val png = instrumentation.context.assets.open("floating/happy.png").use { it.readBytes() }
        return MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/server/features" -> MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                    request.path?.startsWith("/api/search/") == true -> {
                        searches.incrementAndGet()
                        if (!online.get()) MockResponse().setResponseCode(401) else {
                            val item = JSONObject().put("id", "00000000-0000-0000-0000-000000000042").put("type", "IMAGE")
                                .put("originalMimeType", "image/png").put("originalFileName", "recovered.png")
                                .put("updatedAt", "2026-10-21T00:00:00Z")
                                .put("exifInfo", JSONObject().put("description", "Recovered source"))
                            MockResponse().setBody(JSONObject().put("assets", JSONObject().put("items", JSONArray().put(item))).toString())
                        }
                    }
                    request.path?.contains("/thumbnail") == true || request.path?.contains("/original") == true ->
                        MockResponse().setBody(Buffer().write(png))
                    request.path == "/api/users/me" -> MockResponse().setBody("""{"id":"00000000-0000-0000-0000-000000000001"}""")
                    else -> MockResponse().setBody("[]")
                }
            }
            start()
        }
    }

    @Test fun errorOpensThatProfileDirectlyAndRetryKeepsIndependentSelections(): Unit = runBlocking {
        val previous = profiles.snapshots.first()
        val server = server()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            profiles.restore(SourceCatalog())
            val broken = immichProfile("Carousel connection", ImmichConfig(server.url("/").toString(), "recovery-fixture-key"))
            val other = immichProfile("Floating connection", ImmichConfig(server.url("/").toString(), "other-fixture-key"))
            profiles.upsert(broken, true); profiles.upsert(other)
            profiles.selectFloating(other.id)
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                lateinit var model: ArtsViewModel
                scenario.onActivity { model = ViewModelProvider(it)[ArtsViewModel::class.java] }
                await("Source error and recovery controls") {
                    var ready = false
                    scenario.onActivity { ready = model.state.value.browse.error && it.findViewById<View>(R.id.home_source_settings).isShown }
                    ready
                }
                val query = model.state.value.browse.query
                pauseSourceVisual("home-source-error")
                val count = searches.get()
                scenario.onActivity { it.findViewById<View>(R.id.home_source_settings).performClick() }
                val settingsPackage = (broken.configuration as ExtensionConfig).packageName
                await("The failing profile's own settings") { field(settingsPackage, "source_name")?.text?.toString() == broken.name }
                assertEquals(server.url("/").toString(), field(settingsPackage, "server_url")?.text?.toString())
                assertEquals(other.id, profiles.currentFloating().selectedId)
                assertEquals(broken.id, profiles.currentBrowsing().selectedId)
                assertEquals("Opening settings does not retry a failed search", count, searches.get())
                pauseSourceVisual("home-source-error-settings")
                assertTrue(field(settingsPackage, "back")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Returned to the carousel error") {
                    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("${app.packageName}:id/home_retry")
                        ?.any { it.isVisibleToUser } == true
                }
                online.set(true)
                scenario.onActivity { it.findViewById<View>(R.id.home_retry).performClick() }
                await("Explicit retry loads the same collection") { model.state.value.browse.let { !it.loading && !it.error && it.items.isNotEmpty() } }
                scenario.onActivity {
                    assertFalse(it.findViewById<View>(R.id.home_error_actions).isShown)
                    assertEquals("Collection: ${broken.name}", it.findViewById<TextView>(R.id.type).text.toString())
                }
                assertEquals(query, model.state.value.browse.query)
                assertEquals(count + 1, searches.get())
                assertEquals(other.id, profiles.currentFloating().selectedId)
            }
        } finally { profiles.restore(previous); server.shutdown() }
    }

    @Test fun allSourceErrorsOpenSourceManagementRatherThanAnArbitraryMember(): Unit = runBlocking {
        val previous = profiles.snapshots.first()
        val server = server()
        try {
            profiles.restore(SourceCatalog())
            val row = immichProfile("All error member", ImmichConfig(server.url("/").toString(), "all-error-fixture-key"))
            profiles.upsert(row, true); profiles.enableAll(true); profiles.select(ALL_SOURCE_ID)
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                await("All failure recovery") {
                    var ready = false
                    scenario.onActivity { ready = it.findViewById<View>(R.id.home_source_settings).isShown }
                    ready
                }
                scenario.onActivity { assertEquals(app.getString(R.string.sources_title), it.findViewById<TextView>(R.id.home_source_settings).text.toString()) }
                val monitor = instrumentation.addMonitor(SourceSettingsActivity::class.java.name, null, false)
                try {
                    scenario.onActivity { it.findViewById<View>(R.id.home_source_settings).performClick() }
                    val management = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                    assertNotNull("All has several owners; open their management page", management)
                    instrumentation.runOnMainSync { management!!.finish() }
                } finally { instrumentation.removeMonitor(monitor) }
            }
        } finally { profiles.restore(previous); server.shutdown() }
    }

    private fun field(packageName: String, id: String) = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByViewId("$packageName:id/$id")?.firstOrNull()
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue(label, condition())
    }
}
