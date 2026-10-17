package one.njk.sao.floating

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import one.njk.sao.source.*
import one.njk.sao.immichprovider.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real Binder + provider-stream contract against localhost. No personal media/server access. */
class ImmichDeviceTest {
    @get:org.junit.Rule val marquees = PickerMarqueeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val access get() = EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
    private val profiles get() = access.sources()
    private lateinit var previous: SourceCatalog
    private lateinit var server: MockWebServer
    private lateinit var profile: SourceProfile
    private lateinit var source: SwitchingMediaSource
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val searchStatus = AtomicInteger(200)
    private val mediaStatus = AtomicInteger(200)
    private val featuresStatus = AtomicInteger(200)
    private var smart = true
    private var mediaBytes = "GIF89a-synthetic-animation".toByteArray()
    private var kind = "image/gif"
    private var resultCursor: String? = null
    private var slowMedia = false
    private fun result() = """{"assets":{"items":[{"id":"same-asset","type":"${if(kind.startsWith("video/")) "VIDEO" else "IMAGE"}","originalFileName":"reaction.gif","originalMimeType":"$kind","updatedAt":"v1"}],"nextCursor":${resultCursor?.let { "\"$it\"" } ?: "null"}}}"""
    @Before fun setup() = runBlocking {
        previous=profiles.current(); profiles.restore(SourceCatalog()); access.browseSession().reset()
        server=MockWebServer().apply { dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls+=request
                val path=request.requestUrl!!.encodedPath
                return when {
                    path=="/api/server/features" -> MockResponse().setResponseCode(featuresStatus.get()).setBody("""{"smartSearch":$smart,"ocr":$smart}""")
                    path=="/api/server/version" -> MockResponse().setBody("""{"major":3,"minor":3,"patch":1}""")
                    path=="/api/api-keys/me" -> MockResponse().setBody("""{"id":"key","name":"Fixture","permissions":["all"]}""")
                    path=="/api/albums" || path=="/api/tags" -> MockResponse().setBody("[]")
                    path.endsWith("/original") || path.endsWith("/thumbnail") -> if(slowMedia) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else MockResponse().setResponseCode(mediaStatus.get()).setBody(Buffer().write(mediaBytes))
                    else -> MockResponse().setResponseCode(searchStatus.get()).setHeader("Retry-After","3600").setBody(result())
                }
            }
        }; start() }
        profile=immichProfile("Fixture",ImmichConfig(server.url("/").toString(),"fixture-secret"))
        profiles.upsert(profile,true)
        source=SwitchingMediaSource(app,LocalMediaSource(app),access.immichSettings(),FloatingDispatchers())
    }
    @After fun cleanup() = runBlocking { source.shutdown(); profiles.restore(previous); access.browseSession().reset(); server.shutdown() }
    @Test fun publicProfilesContainNoCredentialsAndConfigurationRevisionIsStableOnRename() = runBlocking {
        val config=profile.configuration as ExtensionConfig
        val extension=testExtensions(app).requireInstalled(config)
        val public=ExtensionClient(app,extension.component).request(operation=ExtensionProtocol.PROFILES).toString()
        assertFalse(public.contains("fixture-secret")); assertFalse(public.contains(server.hostName)); assertFalse(public.contains("apiKey"))
        val codec=ExtensionConfigurationCodec(); val stored=codec.encode(config).toString()
        assertFalse(stored.contains("fixture-secret")); assertFalse(stored.contains("127.0.0.1"))
        val renamed=immichProfile("Renamed",profile.testConnection(),config.profile)
        assertEquals(profile.identity,renamed.identity)
        val changed=renamed.changeConnection(profile.testConnection().copy(apiKey="another-secret"))
        assertNotEquals(profile.identity,changed.identity)
        val fresh=SourceProfiles(app,FloatingDispatchers(),mapOf("extension" to codec))
        assertEquals(config,fresh.current().selected!!.configuration)
        try {
            ExtensionClient(app,extension.component).request(config.profile,ExtensionProtocol.CAPABILITIES,
                JSONObject().put("source",profile.identity).put("revision",config.revision)); fail("Accepted stale revision")
        } catch(error: ExtensionFailure) { assertEquals("PROFILE_CHANGED",error.code) }
        assertEquals(0,calls.size)
    }
    @Test fun profilesAreLazyAndNeverMixHeadersMediaOrSelectedItems() = runBlocking {
        val second=immichProfile("Work",profile.testConnection().copy(apiKey="work-key")); profiles.upsert(second)
        assertEquals(0,calls.size)
        val first=source.page(MediaQuery("😂 amazing")).items.single()
        assertEquals("GIF89a-synthetic-animation",source.original(first).bufferedReader().use { it.readText() })
        assertTrue(calls.all { it.getHeader("x-api-key")=="fixture-secret" })
        val before=calls.size
        source.original(first).close(); assertEquals(before,calls.size)
        profiles.select(second.id)
        try { source.original(first); fail("Accepted old source selection") } catch(_: IllegalStateException) { }
        mediaBytes="work-only".toByteArray()
        val work=source.page(MediaQuery()).items.single()
        assertEquals("work-only",source.original(work).bufferedReader().use { it.readText() })
        assertNotEquals(first.id,work.id)
        assertTrue(calls.drop(before).all { it.getHeader("x-api-key")=="work-key" })
    }
    @Test fun gifPngAndVideoUseLosslessHostCacheAndPartialFailuresNeverCommit() = runBlocking {
        for(mime in listOf("image/gif","image/png","video/mp4","video/webm")) {
            kind=mime
            profile=profile.changeConnection(profile.testConnection().copy(apiKey="key-$mime")); profiles.upsert(profile,true)
            val item=source.page(MediaQuery()).items.single()
            mediaBytes=ByteArray(4096) { (it%251).toByte() }
            val file=if(item.isVideo) source.video(item)!! else source.preview(item) as File
            assertArrayEquals(mediaBytes,file.readBytes())
            assertTrue(file.canonicalPath.startsWith(File(app.cacheDir,"source-media").canonicalPath))
            val count=calls.size
            if(item.isVideo) source.video(item) else source.preview(item)
            assertEquals(count,calls.size)
        }
        profile=profile.changeConnection(profile.testConnection().copy(apiKey="bad-stream")); profiles.upsert(profile,true)
        val item=source.page(MediaQuery()).items.single(); mediaStatus.set(401)
        try { source.original(item); fail("Committed failed stream") } catch(error: SourceReadException) { assertEquals(one.njk.sao.R.string.source_extension_auth,error.messageRes) }
        val folder=File(app.cacheDir,"source-media/${digest(profile.identity).take(32)}")
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertEquals(1,calls.count { it.requestUrl!!.encodedPath.endsWith("/original") && it.getHeader("x-api-key")=="bad-stream" })
    }
    @Test fun identicalPreviewAndOriginalShareOneHostCacheEntry() = runBlocking {
        val item=source.page(MediaQuery()).items.single()
        val preview=source.preview(item) as File
        val before=calls.size
        assertArrayEquals(preview.readBytes(),source.original(item).use { it.readBytes() })
        assertEquals(before,calls.size)
        val folder=File(app.cacheDir,"source-media/${digest(profile.identity).take(32)}")
        assertEquals(1,folder.listFiles().orEmpty().size)
    }
    @Test fun retryBudgetIsBoundedAndAuthenticationCooldownAndIdleDoNotRetry() = runBlocking {
        source.searchModes(); calls.clear()
        searchStatus.set(503)
        try { source.page(MediaQuery()); fail("Expected server error") } catch(_: SourceReadException) { }
        // Long server cooldown wins over automatic retry.
        assertEquals(1,calls.count { it.requestUrl!!.encodedPath=="/api/search/metadata" })
        searchStatus.set(401); calls.clear()
        try { source.page(MediaQuery()); fail("Expected auth error") } catch(error: SourceReadException) { assertEquals(one.njk.sao.R.string.source_extension_auth,error.messageRes) }
        val before=calls.size; delay(1200); assertEquals(before,calls.size)
    }
    @Test fun switchingCancelsBlockedStreamWithoutRetryOrPartialFile() = runBlocking {
        kind="video/mp4"; val item=source.page(MediaQuery()).items.single(); slowMedia=true
        val download=launch(Dispatchers.IO) { source.video(item) }
        withTimeout(5000) { while(calls.none { it.requestUrl!!.encodedPath.endsWith("/original") }) delay(20) }
        profiles.select(null); withTimeout(5000) { download.join() }; assertTrue(download.isCancelled)
        val count=calls.size; delay(1100); assertEquals(count,calls.size)
        assertFalse(File(app.cacheDir,"source-media").walkTopDown().any { it.name.endsWith(".pending") })
    }
    @Test fun capabilityFailureRecoversWithoutSwitchingProfileAndUnsupportedModesFallBack() = runBlocking {
        featuresStatus.set(401)
        val state=async { withTimeout(8000) { source.capabilities.first { SearchMode.FILENAME in it.modes } } }
        delay(300); featuresStatus.set(200); smart=false
        source.page(MediaQuery("name"))
        val caps=state.await()
        assertFalse(SearchMode.CONTEXT in caps.modes); assertFalse(SearchMode.OCR in caps.modes)
        assertEquals(SearchMode.FILENAME,caps.defaultMode)
        val search=calls.last { it.requestUrl!!.encodedPath=="/api/search/metadata" }
        assertTrue(search.body.clone().readUtf8().contains("originalFileName"))
    }
    @Test fun realExtensionSettingsRenameOfflineAndReturnOnlyProfileReference() = runBlocking {
        val count=calls.size
        ExtensionForm(profile).use { form ->
            form.await("source_name") { it.text.toString()=="Fixture" }
            form.set("source_name","Offline rename"); form.click("save_connection")
            withTimeout(8000) { while(profiles.current().selected?.name!="Offline rename") delay(30) }
        }
        assertEquals(count,calls.size); assertEquals(profile.identity,profiles.current().selected!!.identity)
    }
    @Test fun moreThanOneHundredExtensionProfilesAreDiscoveredWithoutNetworkCalls() = runBlocking {
        repeat(104) { immichProfile("Library $it",profile.testConnection()) }
        val config=profile.configuration as ExtensionConfig
        val client=ExtensionClient(app,config.component)
        val rows=client.profiles()
        assertEquals(105,rows.size); assertEquals(105,rows.map { it.id }.distinct().size)
        assertEquals(0,calls.size)
        val first=client.request(operation=ExtensionProtocol.PROFILES)
        val cursor=first.getString("next")
        profile.changeConnection(profile.testConnection().copy(apiKey="changed-during-discovery"))
        try {
            client.request(operation=ExtensionProtocol.PROFILES,payload=JSONObject().put("cursor",cursor)); fail("Accepted stale profile-list cursor")
        } catch(error: ExtensionFailure) { assertEquals("PROFILE_CHANGED",error.code) }
    }
    @Test fun profileNamesRemainInOriginalPickerAndSettingsStayInExtension() = runBlocking {
        kind="image/png"; mediaBytes=app.assets.open("floating/happy.png").use { it.readBytes() }
        val work=immichProfile("Work library",profile.testConnection().copy(apiKey="work-profile")); profiles.upsert(work)
        androidx.test.core.app.ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
            var ready=false
            withTimeout(8000) { while(!ready) {
                scenario.onActivity { ready=androidx.lifecycle.ViewModelProvider(it)[PickerViewModel::class.java].state.value.items.size==1 }; delay(30)
            } }
            scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.source_toggle).performClick() }
            withTimeout(8000) { while(profiles.current().profiles.size!=2) delay(30) }
            visualPause("profiles")
        }
        ExtensionForm(profile).use { form ->
            form.await("source_name") { it.text.toString()=="Fixture" }
            visualPause("settings")
        }
    }
    private suspend fun visualPause(stage: String) {
        if(InstrumentationRegistry.getArguments().getString("visualPause")!="true") return
        val resume=File(app.cacheDir,"source-visual-continue").apply { delete() }
        val ready=File(app.cacheDir,"source-visual-$stage").apply { writeText("ready") }
        try { withTimeout(50_000) { while(!resume.exists()) delay(50) } }
        finally { ready.delete(); resume.delete() }
    }

}
