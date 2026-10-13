package one.njk.sao.floating

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Provider
import one.njk.sao.R

class SourcesDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private val profiles get() = access.sources()
    private val dispatchers = FloatingDispatchers()
    private var previous = SourceCatalog()
    private val routers = mutableListOf<SwitchingMediaSource>()
    @Before fun setup() = runBlocking { previous = profiles.current(); profiles.restore(SourceCatalog()); access.browseSession().reset() }
    @After fun cleanup() = runBlocking { routers.forEach { it.shutdown() }; profiles.restore(previous); access.browseSession().reset() }
    private fun profile(name: String, url: String = "http://example.test", key: String = "test-profile-key") =
        SourceProfile(UUID.randomUUID().toString(), name, ImmichConfig(url, key))
    private fun router(factory: MediaSourceFactory = ImmichSourceFactory(app, dispatchers)) =
        SwitchingMediaSource(LocalMediaSource(app), profiles, mapOf("immich" to Provider { factory }), dispatchers).also { routers += it }

    @Test fun manyNamedConnectionsPersistEncryptedAndDeletingSelectedFallsBackToSamples(): Unit = runBlocking {
        val rows = (1..40).map { profile("Library $it", key = "private-key-$it") }
        rows.forEach { profiles.upsert(it) }; profiles.select(rows.last().id)
        val fresh = SourceProfiles(app, dispatchers, mapOf("immich" to ImmichConfigurationCodec()))
        assertEquals(rows.map { it.id }, fresh.current().profiles.map { it.id })
        assertEquals("Library 40", fresh.current().selected!!.name)
        val ciphertext = File(app.noBackupFilesDir, "source-profiles-v1").readBytes().toString(Charsets.UTF_8)
        assertFalse(ciphertext.contains("private-key-")); assertFalse(ciphertext.contains("example.test"))
        profiles.remove(rows.last().id)
        assertNull(profiles.current().selected); assertEquals(39, profiles.current().profiles.size)
        assertEquals(7, router().page(MediaQuery()).items.size)
    }

    @Test fun legacyConnectionMigratesOnceWithMetadataOptOutAndDisabledSelection(): Unit = runBlocking {
        val directory = File(app.cacheDir, "source-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        try {
            EncryptedSettingsFile(context, "immich-connection-v1", "sao-immich-connection-v1").write(
                JSONObject().put("url", "http://legacy.test").put("key", "migration-secret").put("smart", false)
                    .put("album", "album-id").put("enabled", false))
            val migrated = SourceProfiles(context, dispatchers, mapOf("immich" to ImmichConfigurationCodec())).current()
            assertNull(migrated.selectedId)
            val config = migrated.profiles.single().configuration as ImmichConfig
            assertFalse(config.smartSearch); assertEquals("album-id", config.albumId); assertEquals("migration-secret", config.apiKey)
            assertFalse(File(directory, "immich-connection-v1").exists())
            val again = SourceProfiles(context, dispatchers, mapOf("immich" to ImmichConfigurationCodec())).current()
            assertEquals(migrated.profiles.single().id, again.profiles.single().id)
        } finally { directory.deleteRecursively() }
    }

    @Test fun factoriesAreLazyRenameKeepsClientAndSwitchCancelsOldWork(): Unit = runBlocking {
        val first = profile("First"); val slow = profile("Slow", "http://slow.test"); val last = profile("Last")
        listOf(first, slow, last).forEach { profiles.upsert(it) }
        var created = 0; var closed = 0
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val factory = object : MediaSourceFactory {
            override fun create(profile: SourceProfile): ManagedMediaSource {
                created++
                return object : ManagedMediaSource {
                    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage {
                        if (profile.id == slow.id) {
                            entered.complete(Unit)
                            try { awaitCancellation() } finally { cancelled.complete(Unit) }
                        }
                        return MediaPage(emptyList())
                    }
                    override suspend fun filters() = emptyList<String>()
                    override suspend fun preview(item: MediaItem): Any = error("Unused")
                    override suspend fun original(item: MediaItem): InputStream = error("Unused")
                    override suspend fun raster(item: MediaItem): InputStream = error("Unused")
                    override fun close() { closed++ }
                }
            }
        }
        val source = router(factory)
        assertEquals(0, created); profiles.select(first.id); assertEquals(0, created)
        source.filters(); assertEquals(1, created)
        profiles.upsert(first.copy(name = "Renamed")); source.filters(); assertEquals(1, created)
        profiles.select(slow.id)
        val work = launch { source.page(MediaQuery()) }
        withTimeout(3000) { entered.await() }
        profiles.select(last.id)
        withTimeout(3000) { cancelled.await(); work.join() }
        assertTrue(work.isCancelled); assertEquals(2, closed)
        source.filters(); assertEquals(3, created)
        profiles.select(null); source.page(MediaQuery()); assertEquals(3, created); assertEquals(3, closed)
    }

    @Test fun differentProfilesAndChangedCredentialsCannotReuseAssetsOrAcceptOldSelections(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val source = router()
            val first = profile("Home", server.url("/").toString(), "home-key")
            val second = profile("Work", server.url("/").toString(), "work-key")
            val result = """{"assets":{"items":[{"id":"same-asset","type":"IMAGE","originalFileName":"image.png","originalMimeType":"image/png","updatedAt":"same-revision"}]}}"""
            profiles.upsert(first, true); profiles.upsert(second)
            server.enqueue(MockResponse().setBody("[]")); server.enqueue(MockResponse().setBody(result)); server.enqueue(MockResponse().setBody("home-bytes"))
            val home = source.page(MediaQuery()).items.single()
            assertEquals("home-bytes", source.original(home).bufferedReader().use { it.readText() })
            repeat(3) { assertEquals("home-key", server.takeRequest().getHeader("x-api-key")) }
            profiles.select(second.id)
            try { source.original(home); fail("Accepted old profile media") } catch (_: IllegalStateException) { }
            server.enqueue(MockResponse().setBody("[]")); server.enqueue(MockResponse().setBody(result)); server.enqueue(MockResponse().setBody("work-bytes"))
            val work = source.page(MediaQuery()).items.single()
            assertNotEquals(home.id, work.id); assertEquals("work-bytes", source.original(work).bufferedReader().use { it.readText() })
            repeat(3) { assertEquals("work-key", server.takeRequest().getHeader("x-api-key")) }
            profiles.upsert(second.copy(configuration = ImmichConfig(server.url("/").toString(), "changed-key")), true)
            server.enqueue(MockResponse().setBody("[]")); server.enqueue(MockResponse().setBody(result)); server.enqueue(MockResponse().setBody("changed-bytes"))
            val changed = source.page(MediaQuery()).items.single()
            assertNotEquals(work.id, changed.id); assertEquals("changed-bytes", source.original(changed).bufferedReader().use { it.readText() })
            repeat(3) { assertEquals("changed-key", server.takeRequest().getHeader("x-api-key")) }
        } finally { server.shutdown() }
    }

    @Test fun transientReadsRetryOnceButPersistentFailureAndAuthenticationStop(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            profiles.upsert(profile("Retry", server.url("/").toString()), true)
            val source = router()
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody("[]"))
            source.filters(); assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setResponseCode(503))
            try { source.page(MediaQuery()); fail("Persistent failure did not surface") } catch (_: retrofit2.HttpException) { }
            assertEquals(4, server.requestCount); delay(1200); assertEquals(4, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            try { source.page(MediaQuery()); fail("Authentication failure did not surface") } catch (_: retrofit2.HttpException) { }
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun switchingCancelsBlockedHttpDownloadWithoutRetryingOldConnection(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val source = router()
            val first = profile("First", server.url("/").toString())
            val second = profile("Second", server.url("/").toString())
            profiles.upsert(first, true); profiles.upsert(second)
            val result = """{"assets":{"items":[{"id":"blocked-asset","type":"VIDEO","originalFileName":"video.mp4","originalMimeType":"video/mp4","updatedAt":"revision"}]}}"""
            server.enqueue(MockResponse().setBody("[]")); server.enqueue(MockResponse().setBody(result))
            val item = source.page(MediaQuery()).items.single(); server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val download = launch(Dispatchers.Default) { source.video(item) }
            assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS))
            profiles.select(second.id)
            withTimeout(3000) { download.join() }; assertTrue(download.isCancelled)
            delay(1200); assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setBody("[]")); server.enqueue(MockResponse().setBody(result))
            assertEquals(second.identity, source.page(MediaQuery()).items.single().remote!!.sourceId)
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun serverCooldownIsHonoredWithoutRepeatedRequests(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            profiles.upsert(profile("Cooldown", server.url("/").toString()), true)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600"))
            try { router().filters(); fail("Expected cooldown failure") } catch (_: retrofit2.HttpException) { }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun inlinePickerSelectsNamedProfilesAndEditorRenamesWithoutDuplicating(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody(when (request.path) {
                "/api/server/version" -> """{"major":3,"minor":3,"patch":1}"""
                "/api/server/features" -> """{"search":true,"smartSearch":true}"""
                "/api/api-keys/me" -> """{"id":"key","name":"SAO","permissions":["all"]}"""
                "/api/albums", "/api/tags" -> "[]"
                else -> """{"assets":{"items":[]}}"""
            })
        }
        try {
        val first = profile("Home library", server.url("/").toString()); val second = profile("Work library", server.url("/").toString())
        profiles.upsert(first); profiles.upsert(second)
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.source_toggle).performClick() }
            var ready = false; val deadline = SystemClock.elapsedRealtime() + 5000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity {
                    val list = it.findViewById<RecyclerView>(R.id.source_list)
                    ready = list.findViewHolderForAdapterPosition(2) != null
                    if (!ready) list.scrollToPosition(2)
                }; SystemClock.sleep(30)
            }
            assertTrue(ready)
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.source_list).findViewHolderForAdapterPosition(2)!!.itemView.performClick() }
            withTimeout(3000) { while (profiles.current().selectedId != second.id) delay(30) }
            if (InstrumentationRegistry.getArguments().getString("pauseForVisual") == "true") {
                File(app.filesDir, "live_visual_ready").writeText("ready")
                delay(15_000); File(app.filesDir, "live_visual_ready").delete()
            }
        }
        val requestsBeforeRename = server.requestCount
        ActivityScenario.launch<ImmichSettingsActivity>(ImmichSettingsActivity.intent(app, first.id)).use { scenario ->
            var loaded = false; val deadline = SystemClock.elapsedRealtime() + 5000
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { loaded = it.findViewById<EditText>(R.id.source_name).text.toString() == first.name }; SystemClock.sleep(30)
            }
            assertTrue(loaded)
            scenario.onActivity {
                assertTrue(it.findViewById<View>(R.id.delete_source).isShown)
                it.findViewById<EditText>(R.id.source_name).setText("Home renamed")
                it.findViewById<View>(R.id.save_connection).performClick()
            }
            withTimeout(5000) { while (profiles.current().profiles.first { it.id == first.id }.name != "Home renamed") delay(30) }
        }
        assertEquals(first.id, profiles.current().selectedId)
        assertEquals(requestsBeforeRename, server.requestCount)
        assertEquals(2, profiles.current().profiles.size)
        } finally { server.shutdown() }
    }
}
