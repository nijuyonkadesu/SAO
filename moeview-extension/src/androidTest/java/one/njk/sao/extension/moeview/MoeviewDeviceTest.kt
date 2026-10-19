package one.njk.sao.extension.moeview

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import one.njk.sao.moeview.*
import one.njk.sao.moeview.data.*
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Synthetic HTTP/media only; exercises the real signed Binder service, Keystore and reliable pipes. */
class MoeviewDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val runtime=MoeviewRuntime.get(app)
    private val store=runtime.profiles
    private val records=MoeviewRecords(store)
    private val created=mutableListOf<String>()
    private val server=MockWebServer()
    private lateinit var factory: (MoeviewConfig)->BooruApi
    private var pageCount=1
    private val paths=java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val failMedia=java.util.concurrent.atomic.AtomicBoolean()
    private val withoutSample=java.util.concurrent.atomic.AtomicBoolean()
    private val png=ByteArrayOutputStream().use { out -> Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(36,135,190)); compress(Bitmap.CompressFormat.PNG,100,out); recycle()
    }; out.toByteArray() }
    private val formats=mapOf("png" to png,"gif" to instrumentation.context.assets.open("hello.gif").use { it.readBytes() },
        "mp4" to instrumentation.context.assets.open("video.mp4").use { it.readBytes() },"webm" to instrumentation.context.assets.open("video.webm").use { it.readBytes() })
    private val client=ExtensionClient(app,ComponentName(app.packageName,MoeviewExtensionService::class.java.name))
    @Before fun setup() {
        server.dispatcher=object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url=request.requestUrl!!
                paths+=url.encodedPath
                if(url.encodedPath.contains("/media/")) {
                    if(failMedia.compareAndSet(true,false)) return MockResponse().setResponseCode(403)
                    val ext=url.encodedPath.substringAfterLast('.')
                    return MockResponse().setHeader("Content-Type",when(ext) { "gif" -> "image/gif"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; else -> "image/png" })
                        .setBody(Buffer().write(if(url.encodedPath.contains("preview")) png else formats.getValue(ext)))
                }
                val gel=url.encodedPath.contains("index.php"); val dan=url.encodedPath.endsWith("posts.json")
                val page=if(gel) (url.queryParameter("pid")?.toInt() ?: 0)+1 else url.queryParameter("page")?.toInt() ?: 1
                val count=if(page>pageCount) 0 else if(page>1) 1 else url.queryParameter("limit")?.toInt() ?: 20
                val rows=JSONArray((0 until count).map { n ->
                    val id=(page-1)*20+n+1; val ext=listOf("png","gif","mp4","webm")[n%4]
                    val domain=when(request.getHeader("X-Fixture-Board")) {
                        "danbooru" -> "danbooru.donmai.us"; "gelbooru" -> "gelbooru.com"; "yandere" -> "yande.re"; else -> "konachan.com"
                    }
                    JSONObject().put("id",id).put(if(dan) "tag_string" else "tags","smile blue_hair")
                        .put("rating",if(dan) "g" else if(gel) "general" else "s").put("file_ext",ext)
                        .put("file_url","https://$domain/media/fixture-$id.$ext")
                        .put(if(dan) "preview_file_url" else "preview_url","https://$domain/media/preview-$id.png")
                        .apply { if(!withoutSample.get()) put(if(dan) "large_file_url" else "sample_url","https://$domain/media/display-$id.$ext") }
                        .put("created_at",if(gel) "Sun Oct 18 04:00:35 -0500 2026" else if(dan) "2026-10-18T09:00:35Z" else 1792314035)
                })
                return MockResponse().setHeader("Content-Type","application/json").setBody(if(gel) JSONObject().put("post",rows).toString() else rows.toString())
            }
        }
        server.start(); factory=runtime.factory
        runtime.factory={ config ->
            val rewrite=Interceptor { chain ->
                val url=chain.request().url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()
                chain.proceed(MoeviewAuthentication.request(config,chain.request()).newBuilder().url(url).header("X-Fixture-Board",config.board.id).build())
            }
            fun http()=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(rewrite).build()
            BooruApi.create(BooruConfig(minRequestIntervalMs=0,maxRetriesPerTransport=0,allowTransportFallback=false,
                useEdgeTransport=config.proxy && !config.authenticated && config.clearance.isEmpty()),http(),http())
        }
    }
    @After fun cleanup(): Unit=runBlocking {
        created.forEach { id ->
            runtime.invalidate(id)
            store.list().firstOrNull { it.id==id }?.let { store.delete(id,it.revision) }
        }
        runtime.factory=factory; server.shutdown()
    }
    private suspend fun profile(config: MoeviewConfig=MoeviewConfig(),name: String="Moeview fixture"): MoeviewProfile {
        val id=UUID.randomUUID().toString(); created+=id
        return store.save(id,name,config)
    }
    private fun payload(row: MoeviewProfile)=JSONObject().put("revision",row.revision).put("source","fixture:${row.id}:${row.revision}")
    private suspend fun page(row: MoeviewProfile,query: MediaQuery=MediaQuery(),cursor: String?=null)=SourceWire.page(client.request(row.id,ExtensionProtocol.SEARCH,
        payload(row).put("query",SourceWire.query(query)).put("cursor",cursor)))
    private suspend fun uri(row: MoeviewProfile,item: MediaItem,kind: String): Uri=Uri.parse(client.request(row.id,ExtensionProtocol.MEDIA,
        payload(row).put("item",SourceWire.item(item)).put("kind",kind)).getString("uri"))
    private fun read(uri: Uri): ByteArray = app.contentResolver.openFileDescriptor(uri,"r")!!.use { fd ->
        val bytes=android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes(); fd.checkError(); bytes
    }
    private suspend fun fails(code: String,block: suspend ()->Unit) {
        try { block(); fail("Expected $code") } catch(error: ExtensionFailure) { assertEquals(code,error.code) }
    }
    @Test fun encryptedProfilesAreIndependentAndRevisionsChangeOnlyForConfiguration(): Unit=runBlocking {
        val a=profile(MoeviewConfig(board=Board.GELBOORU,login="1234",apiKey="fixture-secret-key"))
        val b=profile(MoeviewConfig(tags="blue_hair"),"Other collection")
        val bytes=java.io.File(app.noBackupFilesDir,"moeview-profiles-v1").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("fixture-secret-key")); assertFalse(bytes.contains("blue_hair"))
        val renamed=store.save(a.id,"Renamed",a.config); assertEquals(a.revision,renamed.revision)
        val changed=store.save(a.id,"Renamed",a.config.copy(tags="smile")); assertNotEquals(a.revision,changed.revision)
        assertEquals(b,store.get(b.id)); fails("PROFILE_CHANGED") { store.delete(a.id,a.revision) }
        assertFalse(changed.toString().contains("fixture-secret-key"))
        val discovered=client.profiles().first { it.id==a.id }
        assertFalse(SourceWire.profile(discovered).toString().contains("fixture-secret-key"))
        assertEquals(0,server.requestCount)
    }
    @Test fun capabilitiesExposeTagMetadataAndOnlySupportedOperations(): Unit=runBlocking {
        val row=profile()
        val caps=SourceWire.capabilities(client.request(row.id,ExtensionProtocol.CAPABILITIES,payload(row)))
        assertEquals(setOf(SearchMode.DESCRIPTION),caps.modes); assertEquals(SearchMode.DESCRIPTION,caps.defaultMode)
        assertTrue(caps.facets.isEmpty()); assertFalse(caps.sequence); assertFalse(caps.random)
        assertEquals(10,caps.categories.size)
        val dan=profile(MoeviewConfig(board=Board.DANBOORU))
        assertTrue(SourceWire.capabilities(client.request(dan.id,ExtensionProtocol.CAPABILITIES,payload(dan))).random)
        assertEquals(0,server.requestCount)
    }
    @Test fun realBinderPagesHaveOpaqueBoundCursorsAndNoSearchResultCache(): Unit=runBlocking {
        val row=profile(MoeviewConfig(tags="blue_hair")); pageCount=2
        val first=page(row,MediaQuery("smile")); assertEquals(20,first.items.size)
        assertEquals("blue_hair smile rating:s",server.takeRequest().requestUrl!!.queryParameter("tags"))
        val second=page(row,MediaQuery("smile"),first.nextCursor); assertEquals("21",second.items.single().remote!!.assetId)
        assertEquals("2",server.takeRequest().requestUrl!!.queryParameter("page"))
        assertTrue(page(row,MediaQuery("smile"),second.nextCursor).items.isEmpty()); server.takeRequest()
        fails("INVALID_RESPONSE") { page(row,MediaQuery("other"),first.nextCursor) }
        val other=profile(); fails("PROFILE_CHANGED") { page(other,MediaQuery("smile"),first.nextCursor) }
        assertEquals(20,page(row,MediaQuery("smile")).items.size); assertEquals(4,server.requestCount)
        assertFalse(first.items.first().asset.contains("https://"))
    }
    @Test fun allMediaFormatsStreamOriginalBytesAndAnimatedPreviews(): Unit=runBlocking {
        val row=profile(); val items=page(row).items.take(4)
        assertEquals(listOf("image/png","image/gif","video/mp4","video/webm"),items.map { it.mimeType })
        items.forEachIndexed { index,item ->
            val ext=listOf("png","gif","mp4","webm")[index]
            assertArrayEquals(formats.getValue(ext),read(uri(row,item,"original")))
            assertArrayEquals(if(ext=="gif") formats.getValue("gif") else png,read(uri(row,item,"preview")))
            if(item.isVideo) assertArrayEquals(formats.getValue(ext),read(uri(row,item,"video")))
            assertEquals("fixture-${index+1}.$ext",item.filename)
        }
    }
    @Test fun displayPreviewsUseLargeImagesAcrossAllBoardsAndOriginalFallbackSharesItsCacheKey(): Unit=runBlocking {
        for(board in Board.entries) {
            val row=profile(MoeviewConfig(board=board)); val item=page(row).items.first()
            val ref=records.reference(row,item.asset)
            assertTrue("${board.label} uses its display image",ref.getString("preview").endsWith("/display-1.png"))
            assertArrayEquals(png,read(uri(row,item,"preview")))
            assertTrue(paths.last().endsWith("/display-1.png"))
            assertArrayEquals(png,read(uri(row,item,"original")))
            assertTrue(paths.last().endsWith("/fixture-1.png"))
        }
        withoutSample.set(true)
        val row=profile(); val item=page(row).items.first()
        val ref=records.reference(row,item.asset)
        assertEquals(ref.getString("file"),ref.getString("preview"))
        assertEquals(item.remote!!.cacheKeys["original"],item.remote!!.cacheKeys["preview"])
        assertArrayEquals(png,read(uri(row,item,"preview")))
    }
    @Test fun tamperedReferencesAndStaleGrantsDoNotMakeNetworkRequests(): Unit=runBlocking {
        val row=profile(); val item=page(row).items.first(); val resource=uri(row,item,"original")
        val calls=server.requestCount
        fails("INVALID_RESPONSE") { uri(row,item.copy(asset="x"+item.asset.drop(1)),"original") }
        val changed=store.save(row.id,row.name,row.config.copy(tags="smile"))
        fails("PROFILE_CHANGED") { uri(row,item,"original") }
        try { read(resource); fail("Expected stale stream") } catch(error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("PROFILE_CHANGED")) }
        assertNotEquals(row.revision,changed.revision); assertEquals(calls,server.requestCount)
    }
    @Test fun gelbooruUsesZeroBasedPagingTextDatesAndRandomIsOneFreshBatch(): Unit=runBlocking {
        val row=profile(MoeviewConfig(board=Board.GELBOORU))
        val first=page(row); assertEquals("2026-10-18T09:00:35Z",first.items.first().remote!!.uploadedAt)
        assertEquals("0",server.takeRequest().requestUrl!!.queryParameter("pid"))
        val random=page(row,MediaQuery(randomRequest="one")); assertNull(random.nextCursor)
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("tags")!!.contains("sort:random"))
        page(row,MediaQuery(randomRequest="two")); assertEquals(3,server.requestCount)
    }
    @Test fun credentialsAndClearanceNeverGoToProxyMediaOrAnotherBoard() {
        val config=MoeviewConfig(board=Board.DANBOORU,login="user",apiKey="secret",clearance="cf_clearance=verified",browserAgent="Browser fixture")
        val api=MoeviewAuthentication.request(config,Request.Builder().url("https://danbooru.donmai.us/posts.json").build())
        assertEquals("cf_clearance=verified",api.header("Cookie")); assertEquals("Browser fixture",api.header("User-Agent")); assertNotNull(api.header("Authorization"))
        listOf("https://moeview.app/edge/danbooru/posts.json","https://cdn.donmai.us/posts.json","https://danbooru.donmai.us/original/image.png","https://gelbooru.com/index.php","http://danbooru.donmai.us/posts.json").forEach { url ->
            val request=MoeviewAuthentication.request(config,Request.Builder().url(url).build())
            assertNull(request.header("Authorization")); assertNull(request.header("Cookie"))
        }
        assertEquals("cf_clearance=ok; __cf_bm=bot",MoeviewAuthentication.cloudflareCookies("session=private; cf_clearance=ok; __cf_bm=bot; password=hidden"))
        assertFalse(MoeviewRuntime.allowedMedia(Board.DANBOORU,"https://evil.donmai.us.example.com/image.png"))
        assertFalse(MoeviewRuntime.allowedMedia(Board.DANBOORU,"http://127.0.0.1/image.png"))
    }
    @Test fun safeProfileCannotBeOverriddenByRatingMetatags(): Unit=runBlocking {
        val row=profile()
        fails("UNSUPPORTED") { page(row,MediaQuery("smile rating:e")) }
        assertEquals(0,server.requestCount)
    }
    @Test fun failedMediaDoesNotPoisonTheFollowingImageOrVideo(): Unit=runBlocking {
        val row=profile(); val items=page(row).items
        failMedia.set(true)
        try { read(uri(row,items[0],"original")); fail("Expected media refusal") }
        catch(error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("HTTP_403")) }
        assertArrayEquals(png,read(uri(row,items[4],"original")))
        assertArrayEquals(formats.getValue("mp4"),read(uri(row,items[2],"original")))
        assertEquals(4,server.requestCount)
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val output=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if(node!=null) { output+=node; repeat(node.childCount) { visit(node.getChild(it)) } } }
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        instrumentation.uiAutomation.windows.forEach { visit(it.root) }
        return output
    }
    private fun waitFor(label: String,condition: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+15_000
        while(!condition() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(60)
        assertTrue(label+"; visible="+nodes().mapNotNull { it.text?.toString() }.take(20),condition())
    }
    private fun click(predicate: (AccessibilityNodeInfo)->Boolean) {
        var target: AccessibilityNodeInfo?=null
        waitFor("Visible clickable control") {
            target=nodes().firstOrNull(predicate)
            while(target!=null && !target!!.isClickable) target=target!!.parent
            target!=null && target!!.isEnabled
        }
        check(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun clickText(text: String)=click { it.text?.toString()==text || it.contentDescription?.toString()==text }
    private fun visual(stage: String) {
        if(InstrumentationRegistry.getArguments().getString("visualPause")!="true") return
        val stages=InstrumentationRegistry.getArguments().getString("visualStages")
        if(stages!=null && stage !in stages.split(',')) return
        val ready=java.io.File(app.cacheDir,"moeview-visual-$stage").apply { writeText("ready") }
        val resume=java.io.File(app.cacheDir,"moeview-visual-continue").apply { delete() }
        try {
            val until=SystemClock.elapsedRealtime()+50_000
            while(!resume.exists() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(50)
            assertTrue("Resume visual capture: $stage",resume.exists())
        } finally { ready.delete(); resume.delete() }
    }
    @Test fun unchangedSaoDiscoversTheApkAndBrowsesItsPresetsAndSettings(): Unit=runBlocking {
        val name="Moeview UI "+UUID.randomUUID().toString().take(6)
        val row=profile(name=name)
        val launch=app.packageManager.getLaunchIntentForPackage("one.njk.sao")!!
        app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("SAO home") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/type" } }
        val previous=nodes().first { it.viewIdResourceName=="one.njk.sao:id/type" }.text.toString().removePrefix("Collection: ")
        try {
            click { it.viewIdResourceName=="one.njk.sao:id/edit" }
            waitFor("Installed extension profiles discovered") { nodes().any { it.text?.toString()==name } }
            clickText(name)
            waitFor("Source selection persisted") { nodes().any { it.contentDescription?.toString()=="$name, selected" } }
            click { it.viewIdResourceName=="one.njk.sao:id/back" }
            waitFor("Moeview focal card and extension presets") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/media_title" && it.text?.toString()=="Konachan · 1" } && nodes().any { it.text?.toString()=="Twintails" } }
            val theme=if(app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
            visual("home-$theme")
            click { it.contentDescription?.toString()?.startsWith("Konachan · 3,")==true }
            waitFor("MP4 uses the existing host player path") { paths.any { it.endsWith("fixture-3.mp4") } && nodes().any { it.viewIdResourceName=="one.njk.sao:id/media_title" && it.text?.toString()=="Konachan · 3" } }
            visual("video-$theme")
            click { it.viewIdResourceName=="one.njk.sao:id/edit" }
            clickText("Edit $name")
            waitFor("Extension-owned Material form") { nodes().any { it.viewIdResourceName=="${app.packageName}:id/source_name" && it.text?.toString()==name } }
            visual("settings-$theme")
            val renamed="$name saved"
            onView(withId(R.id.source_name)).perform(replaceText(renamed),closeSoftKeyboard())
            onView(isAssignableFrom(androidx.core.widget.NestedScrollView::class.java)).perform(swipeUp(),swipeUp())
            onView(withId(R.id.save_connection)).perform(click())
            waitFor("SAO receives the updated extension profile") { nodes().any { it.text?.toString()==renamed } }
            click { it.viewIdResourceName=="one.njk.sao:id/back" }
            waitFor("Return to original home layout") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/type" } }
            assertEquals(row.revision,store.get(row.id).revision)
            assertEquals(renamed,store.get(row.id).name)
        } finally {
            if(nodes().none { it.viewIdResourceName=="one.njk.sao:id/type" }) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            if(nodes().any { it.viewIdResourceName=="one.njk.sao:id/type" }) {
                click { it.viewIdResourceName=="one.njk.sao:id/type" }
                if(nodes().any { it.text?.toString()==previous }) clickText(previous)
                else instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }
}
