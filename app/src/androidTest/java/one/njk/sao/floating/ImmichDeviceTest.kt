package one.njk.sao.floating

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import okio.Buffer

class ImmichDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private val settings get() = access.immichSettings()
    private lateinit var server: MockWebServer
    private var previous = SourceCatalog()
    private lateinit var config: ImmichConfig
    private val id = "ca9a1ff5-f185-4d1d-8e5a-667a2d14bf50"
    private val tag = """{"id":"emoji-tag","name":"😂","value":"reactions/😂"}"""
    private fun result(cursor: String? = null, gif: Boolean = false) = """{"assets":{"items":[{"id":"$id","originalFileName":"reaction.${if (gif) "gif" else "png"}","originalMimeType":"image/${if (gif) "gif" else "png"}","type":"${if (gif) "VIDEO" else "IMAGE"}","updatedAt":"2026-10-09T00:00:00Z","tags":[$tag]}],"nextCursor":${cursor?.let { "\"$it\"" } ?: "null"}}}"""
    private fun source() = SwitchingMediaSource(app, LocalMediaSource(app), settings, FloatingDispatchers())
    @Before fun setup() = runBlocking {
        previous = access.sources().current(); server = MockWebServer(); server.start()
        config = ImmichConfig(server.url("/").toString(), "device-contract-key")
        settings.save(config); access.browseSession().reset()
    }
    @After fun cleanup() = runBlocking { access.sources().restore(previous); access.browseSession().reset(); server.shutdown() }

    @Test fun connectionIsEncryptedOutsideBackupAndLocalSwitchRetainsCredentials() = runBlocking {
        val ciphertext = File(app.noBackupFilesDir, "source-profiles-v1").readBytes()
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains(config.apiKey))
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains(config.serverUrl))
        val fresh = ImmichTestSettings(SourceProfiles(app, FloatingDispatchers(), mapOf("immich" to ImmichConfigurationCodec())))
        assertEquals(config.apiKey, fresh.current()?.apiKey)
        settings.save(config, enabled = false)
        assertEquals(7, source().page(MediaQuery()).items.size)
        assertEquals(config.apiKey, settings.current()?.apiKey)
    }
    @Test fun explicitFilenameSearchCombinesEmojiTagFilterAndPreservesCursor() = runBlocking {
        server.enqueue(MockResponse().setBody("[$tag]")); server.enqueue(MockResponse().setBody(result("next-page")))
        val source = source(); val query = MediaQuery("😂", filter = "😂", mode = SearchMode.FILENAME)
        val page = source.page(query)
        assertEquals("next-page", page.nextCursor); assertEquals(listOf("😂"), page.items.single().emojis)
        assertEquals("/api/tags", server.takeRequest()!!.path)
        val first = server.takeRequest()!!.body.readUtf8()
        assertTrue(first.contains("😂")); assertTrue(first.contains("emoji-tag")); assertTrue(first.contains("\"originalFileName\""))
        assertFalse(first.contains("\"description\"")); assertFalse(first.contains("\"or\""))
        assertTrue(first.contains("\"trashedAt\":{\"eq\":null}")); assertFalse(first.contains("isTrashed"))
        server.enqueue(MockResponse().setBody(result()))
        source.page(query, page.nextCursor)
        assertTrue(server.takeRequest()!!.body.readUtf8().contains("\"cursor\":\"next-page\""))
        assertEquals(3, server.requestCount)
    }
    @Test fun smartSearchPreservesEmojiMixedTextAndSelectedTagFilter() = runBlocking {
        val source = source()
        server.enqueue(MockResponse().setBody("[$tag]"))
        for (text in listOf("😂", "☀️", "👩🏽‍💻", "happy 😂", "😂 😭")) {
            server.enqueue(MockResponse().setBody(result()))
            val page = source.page(MediaQuery(text, filter = "😂"))
            assertEquals(1, page.items.size); assertNull(page.nextCursor)
            if (text == "😂") assertEquals("/api/tags", server.takeRequest()!!.path)
            val request = server.takeRequest()!!
            assertEquals("/api/search/smart", request.path)
            val json = org.json.JSONObject(request.body.readUtf8())
            assertEquals(text, json.getString("query"))
            assertEquals(100, json.getInt("size")); assertEquals(1, json.getInt("page")); assertFalse(json.has("cursor")); assertFalse(json.has("filter"))
            assertFalse(json.has("or"))
            assertEquals("emoji-tag", json.getJSONArray("tagIds").getString(0))
        }
        assertEquals(6, server.requestCount)
    }
    @Test fun gifPreviewAndExportInputKeepOriginalAnimationAndAccountIsolation() = runBlocking {
        val source = source()
        server.enqueue(MockResponse().setBody("[$tag]")); server.enqueue(MockResponse().setBody(result(gif = true)))
        val item = source.page(MediaQuery()).items.single()
        server.takeRequest(); server.takeRequest()
        server.enqueue(MockResponse().setBody("GIF89a-original-animation"))
        val preview = source.preview(item) as File
        assertEquals("GIF89a-original-animation", preview.readText())
        assertEquals("/api/assets/$id/original?edited=false", server.takeRequest()!!.path)
        assertEquals(preview.readText(), source.original(item).bufferedReader().use { it.readText() })
        settings.save(config.copy(apiKey = "different-account-key"))
        try { source.preview(item); fail("Cross-account asset accepted") } catch (_: IllegalStateException) { }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
    @Test fun transparentRemotePngKeepsAlphaInStickerExport() = runBlocking {
        val source = source()
        server.enqueue(MockResponse().setBody("[$tag]")); server.enqueue(MockResponse().setBody(result()))
        val item = source.page(MediaQuery()).items.single(); server.takeRequest(); server.takeRequest()
        val sample = LocalMediaSource(app).all().first { !it.animated }
        val bytes = app.assets.open(sample.asset).use { it.readBytes() }
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        val exported = MediaExports(app, source).prepare(item, Rendition.STICKER_PNG)
        assertEquals("/api/assets/$id/original?edited=false", server.takeRequest()!!.path)
        val bitmap = android.graphics.BitmapFactory.decodeFile(exported.file.path)
        try { assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(16, 16))) } finally { bitmap.recycle() }
    }
    @Test fun pickerReturnsToSameRemotePageAndSearchAcrossCloseWithoutRequestsOrDiskWrites() = runBlocking {
        server.enqueue(MockResponse().setBody("[$tag]")); server.enqueue(MockResponse().setBody(result()))
        // Activity filters and page requests can arrive in either order; cache tags first.
        val sharedSource = source(); sharedSource.filters(); server.takeRequest()
        // The injected source has its own tag cache; allow its first GET and search in either order.
        server.enqueue(MockResponse().setBody(result()))
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                MockResponse().setBody(if (request.path == "/api/tags") "[$tag]" else result())
        }
        val file = File(app.noBackupFilesDir, "source-profiles-v1"); val before = file.readBytes(); val modified = file.lastModified()
        fun awaitLoaded(scenario: ActivityScenario<PickerActivity>) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000; var count = 0
            while (count != 1 && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { count = (it.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.media_grid).adapter as MediaCarouselAdapter).itemCount }
                Thread.sleep(30)
            }
            assertEquals(1, count)
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { awaitLoaded(it) }
        val count = server.requestCount
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { awaitLoaded(it) }
        assertEquals(count, server.requestCount)
        assertArrayEquals(before, file.readBytes()); assertEquals(modified, file.lastModified())
    }

    @Test fun inlineSourcePanelSwitchesBackendAndSelectedRemotePhotoCopiesImmediately() = runBlocking {
        settings.save(config, enabled = false)
        val sample = LocalMediaSource(app).all().first { !it.animated }
        val bytes = app.assets.open(sample.asset).use { it.readBytes() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                request.path == "/api/tags" -> MockResponse().setBody("[$tag]")
                request.path?.contains("/thumbnail") == true || request.path?.contains("/original") == true ->
                    MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                else -> MockResponse().setBody(result())
            }
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            fun waitFor(condition: (PickerActivity) -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 8000; var ready = false
                while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = condition(it) }; Thread.sleep(30)
                }
                assertTrue(ready)
            }
            fun capture(name: String) {
                // Use the existing instrumentation owner; a second UIAutomator process cannot
                // register another UiAutomationService while this test is attached.
                val image = instrumentation.uiAutomation.takeScreenshot()
                val file = File(app.getExternalFilesDir(null), "$name.png")
                file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                    .executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name.png")).use { it.readBytes() }
            }
            waitFor { (it.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.media_grid).adapter as MediaCarouselAdapter).itemCount == 7 }
            Thread.sleep(250); capture("sao-immich-picker-samples")
            scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.source_toggle).performClick() }
            waitFor { it.findViewById<android.view.View>(one.njk.sao.R.id.source_panel).visibility == android.view.View.VISIBLE }
            capture("sao-immich-picker-source")
            waitFor {
                val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.source_list)
                val ready = list.findViewHolderForAdapterPosition(1) != null
                if (!ready) list.scrollToPosition(1)
                ready
            }
            scenario.onActivity { val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.source_list)
                list.findViewHolderForAdapterPosition(1)!!.itemView.performClick() }
            waitFor { it.findViewById<android.widget.TextView>(one.njk.sao.R.id.source_toggle).text.toString().contains("Immich") }
            scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.source_back).performClick() }
            waitFor { val grid = it.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.media_grid)
                val adapter = grid.adapter as MediaCarouselAdapter
                adapter.itemCount == 1 && grid.findViewHolderForAdapterPosition(0)?.itemView?.performClick() == true }
            waitFor { it.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri?.toString()?.contains("immich_") == true }
            Thread.sleep(250); capture("sao-immich-picker-remote")
            scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.search_toggle).performClick() }
            waitFor { it.findViewById<android.view.View>(one.njk.sao.R.id.search_field).visibility == android.view.View.VISIBLE }
            scenario.onActivity {
                assertEquals(android.view.View.GONE, it.findViewById<android.view.View>(one.njk.sao.R.id.source_toggle).visibility)
                assertEquals(android.view.View.GONE, it.findViewById<android.view.View>(one.njk.sao.R.id.source_panel).visibility)
            }
        }
        Unit
    }

    @Test fun connectionSettingsVerifyServerBeforeSavingAndCanSwitchBackToLocal() = runBlocking {
        settings.save(config, enabled = false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody(when (request.path) {
                "/api/server/version" -> """{"major":3,"minor":3,"patch":1}"""
                "/api/server/features" -> """{"search":true,"smartSearch":true}"""
                "/api/api-keys/me" -> """{"id":"key","name":"SAO","permissions":["all"]}"""
                "/api/albums" -> "[]"
                else -> result()
            })
        }
        ActivityScenario.launch<ImmichSettingsActivity>(ImmichSettingsActivity.intent(app, settings.profiles.current().profiles.first().id)).use { scenario ->
            fun awaitStatus(resource: Int) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000; var ready = false
                while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = it.findViewById<android.widget.TextView>(one.njk.sao.R.id.status).text.toString().startsWith(it.getString(resource)) }
                    Thread.sleep(30)
                }
                assertTrue(ready)
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000; var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { ready = it.findViewById<android.widget.EditText>(one.njk.sao.R.id.server_url).text.toString() == config.serverUrl }
                Thread.sleep(30)
            }
            assertTrue(ready)
            scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.test_connection).performClick() }
            awaitStatus(one.njk.sao.R.string.immich_connected)
            assertNull(settings.profiles.current().selectedId)
            scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.save_connection).performClick() }
            awaitStatus(one.njk.sao.R.string.immich_saved)
            assertNotNull(settings.profiles.current().selectedId); assertEquals(config.apiKey, settings.current()!!.apiKey)
            scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.use_local).performClick() }
            awaitStatus(one.njk.sao.R.string.immich_using_local)
            assertNull(settings.profiles.current().selectedId); assertEquals(config.apiKey, settings.current()!!.apiKey)
        }
        Unit
    }
}
