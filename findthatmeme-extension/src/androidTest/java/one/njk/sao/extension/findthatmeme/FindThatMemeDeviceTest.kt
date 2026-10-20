package one.njk.sao.extension.findthatmeme

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.mockwebserver.*
import okio.Buffer
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic HTTP only. Exercises the signed Binder service, private profiles and reliable media pipes. */
class FindThatMemeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val runtime = FindThatMemeRuntime.get(app)
    private val store = runtime.profiles
    private val client = ExtensionClient(app, ComponentName(app.packageName, FindThatMemeExtensionService::class.java.name))
    private val server = MockWebServer()
    private val created = mutableListOf<String>()
    private var searches: (String, Int) -> MockResponse = { _, offset -> json(rows(offset, if (offset == 0) 50 else 1)) }
    private val nextMedia = AtomicReference<MockResponse?>()
    private val png = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(36, 135, 190)); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
        }
        out.toByteArray()
    }
    private val formats by lazy { mapOf("png" to png, "gif" to asset("hello.gif"), "mp4" to asset("video.mp4"), "webm" to asset("video.webm")) }
    private fun asset(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }
    private fun json(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())

    @Before fun setup() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/search") {
                    val body = JSONObject(request.body.clone().readUtf8())
                    return searches(body.getString("search"), body.getInt("offset"))
                }
                nextMedia.getAndSet(null)?.let { return it }
                val ext = request.requestUrl!!.pathSegments.last().substringAfterLast('.')
                return MockResponse().setHeader("Content-Type", "binary/octet-stream").setBody(Buffer().write(formats.getValue(ext)))
            }
        }
        server.start()
        runtime.fixture = FindThatMemeApi(FindThatMemeApi.client().newBuilder().addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder()
                .scheme("http").host("127.0.0.1").port(server.port).build()).build())
        }).build())
    }
    @After fun cleanup(): Unit = runBlocking {
        created.forEach { id -> store.list().firstOrNull { it.id == id }?.let { store.delete(id, it.revision) } }
        runtime.fixture?.http?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        runtime.fixture = null
        server.shutdown()
    }
    private fun rows(offset: Int, count: Int) = JSONArray((0 until count).map { n ->
        val number = offset + n + 1
        val ext = listOf("png", "gif", "mp4", "webm")[n % 4]
        JSONObject().put("id", UUID(0, number.toLong()).toString()).put("short_id", "entry-$number").put("source_site", "Fixture")
            .put("image_url", "https://s3.thehackerblog.com/findthatmeme/fixture-$number.$ext")
            .put("type", if (ext in setOf("mp4", "webm")) "VIDEO" else "IMAGE")
            .put("thumbnail", "poster-$number.png").put("nsfw_porn", 0).put("nsfw_hentai", 0).put("nsfw_sexy", 0)
    })
    private suspend fun profile(config: FindThatMemeConfig = FindThatMemeConfig(), name: String = "Meme fixture"): FindThatMemeProfile {
        val id = UUID.randomUUID().toString(); created += id
        return store.save(id, name, config)
    }
    private fun payload(row: FindThatMemeProfile) = JSONObject().put("revision", row.revision).put("source", "fixture:${row.id}:${row.revision}")
    private suspend fun page(row: FindThatMemeProfile, query: MediaQuery = MediaQuery(), cursor: String? = null) = SourceWire.page(
        client.request(row.id, ExtensionProtocol.SEARCH, payload(row).put("query", SourceWire.query(query)).put("cursor", cursor)))
    private suspend fun uri(row: FindThatMemeProfile, item: MediaItem, kind: String) = Uri.parse(client.request(row.id,
        ExtensionProtocol.MEDIA, payload(row).put("item", SourceWire.item(item)).put("kind", kind)).getString("uri"))
    private fun read(uri: Uri): ByteArray = app.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes(); fd.checkError(); bytes
    }
    private suspend fun fails(code: String, operation: suspend () -> Unit) {
        try { operation(); fail("Expected $code") } catch (error: ExtensionFailure) { assertEquals(code, error.code) }
    }

    @Test fun profilesAreIndependentAndCapabilitiesHideUnsupportedFeatures(): Unit = runBlocking {
        val first = profile(name = "First source")
        val second = profile(FindThatMemeConfig("cats", true), "Other source")
        val profiles = client.request("", ExtensionProtocol.PROFILES, JSONObject()).getJSONArray("profiles")
        val decoded = List(profiles.length()) { SourceWire.profile(profiles.getJSONObject(it)) }
        assertTrue(decoded.any { it.id == first.id && !it.nsfw })
        assertTrue(decoded.any { it.id == second.id && it.nsfw })
        val caps = SourceWire.capabilities(client.request(first.id, ExtensionProtocol.CAPABILITIES, payload(first)))
        assertEquals(setOf(SearchMode.DESCRIPTION), caps.modes)
        assertEquals(setOf(SearchFacet.MEDIA), caps.facets)
        assertEquals(SearchMode.DESCRIPTION, caps.defaultMode)
        assertFalse(caps.random); assertFalse(caps.sequence); assertFalse(caps.imports)
        assertTrue(caps.suggestionModes.isEmpty()); assertEquals(6, caps.categories.size)
        val renamed = store.save(first.id, "Renamed", first.config)
        assertEquals(first.revision, renamed.revision)
        assertEquals("cats", store.get(second.id).config.terms)
        assertEquals(0, server.requestCount)
    }

    @Test fun paginationPreservesWholeQueriesAndDoesNotCacheSearches(): Unit = runBlocking {
        val row = profile(FindThatMemeConfig("cat"))
        val query = MediaQuery("i have no idea 😂", randomRequest = "unsupported-lucky")
        val first = page(row, query)
        assertEquals(50, first.items.size); assertNotNull(first.nextCursor)
        val request = server.takeRequest()
        assertEquals("true", request.getHeader("X-CSRF-Validation-Header"))
        assertEquals("cat i have no idea 😂", JSONObject(request.body.readUtf8()).getString("search"))
        val second = page(row, query, first.nextCursor)
        assertEquals(1, second.items.size); assertNull(second.nextCursor)
        assertTrue(first.items.map { it.id }.intersect(second.items.map { it.id }.toSet()).isEmpty())
        assertEquals(50, JSONObject(server.takeRequest().body.readUtf8()).getInt("offset"))
        page(row, query)
        assertEquals(3, server.requestCount)
    }

    @Test fun mediaFiltersUseBackendSyntaxAndNeverReturnTheWrongKind(): Unit = runBlocking {
        val row = profile()
        val videos = page(row, MediaQuery("this is fine", constraints = SearchConstraints(media = SearchMedia.VIDEOS)))
        assertTrue(videos.items.isNotEmpty()); assertTrue(videos.items.all { it.isVideo })
        assertEquals("this is fine type:video", JSONObject(server.takeRequest().body.readUtf8()).getString("search"))
        val images = page(row, MediaQuery("😂", constraints = SearchConstraints(media = SearchMedia.IMAGES)))
        assertTrue(images.items.all { !it.isVideo })
        assertEquals("😂 type:image", JSONObject(server.takeRequest().body.readUtf8()).getString("search"))
    }

    @Test fun changedQueriesProfilesAndTamperedCursorsFailBeforeHttp(): Unit = runBlocking {
        val row = profile()
        val first = page(row, MediaQuery("cat"))
        fails("INVALID_RESPONSE") { page(row, MediaQuery("dog"), first.nextCursor) }
        fails("INVALID_RESPONSE") { page(row, MediaQuery("cat"), "invalid-token") }
        val other = profile()
        fails("PROFILE_CHANGED") { page(other, MediaQuery("cat"), first.nextCursor) }
        val changed = store.save(row.id, row.name, FindThatMemeConfig("new scope"))
        fails("PROFILE_CHANGED") { page(row) }
        assertNotEquals(row.revision, changed.revision); assertEquals(1, server.requestCount)
    }

    @Test fun duplicatePagesAndMalformedApiResponsesFailWithoutRetryLoops(): Unit = runBlocking {
        val row = profile()
        searches = { _, _ -> json(rows(0, 50)) }
        val first = page(row)
        fails("INVALID_RESPONSE") { page(row, cursor = first.nextCursor) }
        searches = { _, _ -> MockResponse().setBody("<html>Service unavailable</html>") }
        fails("INVALID_RESPONSE") { page(row) }
        searches = { _, _ -> json(rows(0, 51)) }
        fails("INVALID_RESPONSE") { page(row) }
        searches = { _, _ -> MockResponse().setBody(" ".repeat(2 * 1024 * 1024 + 1)) }
        fails("INVALID_RESPONSE") { page(row) }
        assertEquals(5, server.requestCount)
    }

    @Test fun sensitiveScoresAreFilteredWithoutLosingContinuationAndFlagAllRatingProfiles(): Unit = runBlocking {
        searches = { _, offset -> json(rows(offset, if (offset == 0) 50 else 1).apply {
            for (i in 0 until length()) getJSONObject(i).put("nsfw_porn", if (offset == 0) 90 else -1)
        }) }
        val row = profile()
        val first = page(row)
        assertTrue(first.items.isEmpty()); assertNotNull(first.nextCursor)
        assertEquals(1, page(row, cursor = first.nextCursor).items.size)
        val all = profile(FindThatMemeConfig(sensitive = true))
        assertTrue(all.publicProfile().nsfw); assertEquals(50, page(all).items.size)
    }

    @Test fun originalGifAndVideosStreamExactBytesWithSharedStaticCacheKeys(): Unit = runBlocking {
        val row = profile(); val items = page(row).items.take(4)
        for ((index, item) in items.withIndex()) {
            val ext = listOf("png", "gif", "mp4", "webm")[index]
            assertEquals("fixture-${index + 1}.$ext", item.filename)
            assertArrayEquals(formats.getValue(ext), read(uri(row, item, "original")))
            assertArrayEquals(if (item.isVideo) png else formats.getValue(ext), read(uri(row, item, "preview")))
            if (item.isVideo) assertArrayEquals(formats.getValue(ext), read(uri(row, item, "video")))
            else assertEquals(item.remote!!.cacheKeys["original"], item.remote!!.cacheKeys["preview"])
        }
        assertTrue(items[1].animated)
    }

    @Test fun invalidBodiesAndFailedTransfersDoNotPoisonTheNextPaste(): Unit = runBlocking {
        val row = profile(); val items = page(row).items
        nextMedia.set(MockResponse().setHeader("Content-Type", "image/png").setBody("<html>Cloudflare error</html>"))
        try { read(uri(row, items[0], "original")); fail("Expected invalid body") }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("INVALID_RESPONSE")) }
        nextMedia.set(MockResponse().setResponseCode(403))
        try { read(uri(row, items[0], "original")); fail("Expected refused media") }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("HTTP_403")) }
        assertArrayEquals(formats.getValue("gif"), read(uri(row, items[1], "original")))
        assertArrayEquals(formats.getValue("mp4"), read(uri(row, items[2], "original")))
        assertEquals(5, server.requestCount)
    }

    @Test fun oversizedStreamsAndStaleGrantsAreRejected(): Unit = runBlocking {
        val row = profile(); val item = page(row).items.first { it.isVideo }
        nextMedia.set(MockResponse().setBody("").setHeader("Content-Length", 64L * 1024 * 1024 + 1))
        try { read(uri(row, item, "original")); fail("Expected size limit") }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("MEDIA_TOO_LARGE")) }
        val large = ByteArray(8 * 1024 * 1024 + 1)
        png.copyInto(large, endIndex = 12)
        nextMedia.set(MockResponse().setChunkedBody(Buffer().write(large), 64 * 1024))
        try { read(uri(row, item, "preview")); fail("Expected streamed size limit") }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("MEDIA_TOO_LARGE")) }
        val stale = uri(row, item, "original")
        store.save(row.id, row.name, FindThatMemeConfig("changed"))
        try { read(stale); fail("Expected stale grant") }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("PROFILE_CHANGED")) }
        assertEquals(3, server.requestCount)
    }

    @Test fun cancellationStopsAStalledHttpRequestAndFollowingSearchRecovers(): Unit = runBlocking {
        val row = profile()
        searches = { _, _ -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val request = async { page(row) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
        request.cancelAndJoin()
        withTimeout(5000) { while (runtime.api.http.dispatcher.runningCallsCount() != 0) delay(20) }
        searches = { _, _ -> json(rows(0, 1)) }
        assertEquals(1, page(row).items.size)
    }

    @Test fun cancellationAlsoStopsSlowSearchBodiesAndProviderStreams(): Unit = runBlocking {
        val row = profile()
        searches = { _, _ -> json(rows(0, 1)).throttleBody(1, 1, TimeUnit.SECONDS) }
        val slow = async { page(row) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
        delay(200)
        withTimeout(5000) { slow.cancelAndJoin() }
        withTimeout(5000) { while (runtime.api.http.dispatcher.runningCallsCount() != 0) delay(20) }
        searches = { _, _ -> json(rows(0, 1)) }
        val item = page(row).items.first()
        server.takeRequest()
        nextMedia.set(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val signal = CancellationSignal()
        val descriptor = app.contentResolver.openFileDescriptor(uri(row, item, "original"), "r", signal)!!
        val reading = async(Dispatchers.IO) { runCatching { descriptor.use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes(); it.checkError()
        } } }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
        signal.cancel()
        descriptor.close() // SAO closes the returned descriptor as well as cancelling its opener.
        withTimeout(5000) { reading.await() }
        withTimeout(5000) { while (runtime.api.http.dispatcher.runningCallsCount() != 0) delay(20) }
        assertArrayEquals(png, read(uri(row, item, "original")))
    }

    @Test fun rateLimitCooldownIsReturnedWithoutAnExtensionRetry(): Unit = runBlocking {
        val row = profile()
        searches = { _, _ -> MockResponse().setResponseCode(429).setHeader("Retry-After", "30") }
        try { page(row); fail("Expected rate limit") } catch (error: ExtensionFailure) {
            assertEquals("HTTP_429", error.code); assertEquals("30", error.retryAfter)
        }
        assertEquals(1, server.requestCount)
        searches = { _, _ -> json(rows(0, 1)) }
        assertEquals(1, page(row).items.size)
    }

    @Test fun unsafeUrlsTamperedItemsAndUnsupportedActionsNeverFetchMedia(): Unit = runBlocking {
        listOf("http://s3.thehackerblog.com/findthatmeme/a.png", "https://s3.thehackerblog.com.evil.test/findthatmeme/a.png",
            "https://127.0.0.1/findthatmeme/a.png", "https://s3.thehackerblog.com/other/a.png",
            "https://user@s3.thehackerblog.com/findthatmeme/a.png").forEach { assertFalse(FindThatMemeApi.allowedMedia(it)) }
        searches = { _, _ -> json(rows(0, 4).apply { getJSONObject(0).put("image_url", "https://evil.test/a.png") }) }
        val row = profile(); val result = page(row)
        assertEquals(3, result.items.size)
        val item = result.items.first()
        fails("INVALID_RESPONSE") { uri(row, item.copy(asset = "tampered"), "original") }
        fails("INVALID_RESPONSE") { uri(row, item.copy(mimeType = "video/mp4"), "original") }
        fails("UNSUPPORTED") { client.request(row.id, ExtensionProtocol.SUGGESTIONS, payload(row)) }
        assertEquals(1, server.requestCount)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val output = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if (node != null) { output += node; repeat(node.childCount) { visit(node.getChild(it)) } } }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        instrumentation.uiAutomation.windows.forEach { visit(it.root) }
        return output
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(80)
        assertTrue("$label: " + nodes().mapNotNull { it.text?.toString() }.take(25), condition())
    }
    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var target: AccessibilityNodeInfo? = null
        waitFor("Clickable control") {
            target = nodes().firstOrNull(predicate)
            while (target != null && !target!!.isClickable) target = target!!.parent
            target?.isVisibleToUser == true && target?.isEnabled == true
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun show(id: String) {
        waitFor("Visible $id") {
            val node = nodes().firstOrNull { it.viewIdResourceName == id }
            if (node?.isVisibleToUser == true) true else {
                if (node != null) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                else nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
        }
    }
    private fun visual(stage: String) {
        if (InstrumentationRegistry.getArguments().getString("visualPause") != "true") return
        val ready = java.io.File(app.cacheDir, "findthatmeme-visual-$stage").apply { writeText("ready") }
        val resume = java.io.File(app.cacheDir, "findthatmeme-visual-continue").apply { delete() }
        try {
            val until = SystemClock.elapsedRealtime() + 50_000
            while (!resume.exists() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            assertTrue("Visual capture resumed", resume.exists())
        } finally { ready.delete(); resume.delete() }
    }

    @Test fun unchangedSaoDiscoversBrowsesAndEditsTheNewExtension(): Unit = runBlocking {
        val name = "FindThatMeme UI " + UUID.randomUUID().toString().take(6)
        val row = profile(name = name)
        app.startActivity(app.packageManager.getLaunchIntentForPackage("one.njk.sao")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("SAO home") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/type" } }
        click { it.viewIdResourceName == "one.njk.sao:id/edit" }
        waitFor("Discovered source") { nodes().any { it.text?.toString() == name } }
        click { it.text?.toString() == name }
        waitFor("Selected source") { nodes().any { it.contentDescription?.toString() == "$name, selected" } }
        click { it.viewIdResourceName == "one.njk.sao:id/back" }
        waitFor("Host carousel displays new source") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/media_title" && it.text?.toString() == "Fixture · entry-1" } }
        visual("home")
        click { it.viewIdResourceName == "one.njk.sao:id/edit" }
        click { it.contentDescription?.toString() == "Edit $name" }
        waitFor("Extension settings") { nodes().any { it.viewIdResourceName == "${app.packageName}:id/source_name" && it.text?.toString() == name } }
        visual("settings")
        val field = nodes().first { it.viewIdResourceName == "${app.packageName}:id/source_name" }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$name saved")
        }))
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitFor("Unsaved changes dialog") { nodes().any { it.text?.toString() == "Keep editing" } }
        click { it.text?.toString() == "Keep editing" }
        show("${app.packageName}:id/save_source")
        click { it.viewIdResourceName == "${app.packageName}:id/save_source" }
        waitFor("Saved profile returned to SAO") { nodes().any { it.text?.toString() == "$name saved" } }
        assertEquals(row.revision, store.get(row.id).revision)
        assertEquals("$name saved", store.get(row.id).name)
        click { it.viewIdResourceName == "one.njk.sao:id/back" }
        waitFor("Back on home") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/type" } }
        click { it.viewIdResourceName == "one.njk.sao:id/settings" }
        show("one.njk.sao:id/preview")
        click { it.viewIdResourceName == "one.njk.sao:id/preview" }
        waitFor("Floating source control") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/source_toggle" } }
        if (nodes().none { it.viewIdResourceName == "one.njk.sao:id/source_toggle" && it.contentDescription?.toString()?.contains("$name saved") == true }) {
            click { it.viewIdResourceName == "one.njk.sao:id/source_toggle" }
            click { it.text?.toString() == "$name saved" }
        }
        if (nodes().any { it.viewIdResourceName == "one.njk.sao:id/source_back" && it.isVisibleToUser }) {
            click { it.viewIdResourceName == "one.njk.sao:id/source_back" }
        }
        waitFor("Existing floating renderer displays new source") {
            nodes().any { it.viewIdResourceName == "one.njk.sao:id/selection_title" && it.text?.toString() == "Fixture · entry-1" }
        }
        click { it.viewIdResourceName == "one.njk.sao:id/search_toggle" }
        assertFalse("Lucky is unsupported", nodes().any { it.viewIdResourceName == "one.njk.sao:id/search_lucky" && it.isVisibleToUser })
        visual("floating")
        click { it.viewIdResourceName == "one.njk.sao:id/close" }
    }
}
