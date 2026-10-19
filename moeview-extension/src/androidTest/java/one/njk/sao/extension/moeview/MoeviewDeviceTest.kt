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
    private val onlyStatic=java.util.concurrent.atomic.AtomicBoolean()
    private val holdPreviews=java.util.concurrent.atomic.AtomicBoolean()
    private val previewGate=java.util.concurrent.CountDownLatch(1)
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
                    if(holdPreviews.get()) previewGate.await(90,java.util.concurrent.TimeUnit.SECONDS)
                    if(failMedia.compareAndSet(true,false)) return MockResponse().setResponseCode(403)
                    val ext=url.encodedPath.substringAfterLast('.')
                    return MockResponse().setHeader("Content-Type",when(ext) { "gif" -> "image/gif"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; else -> "image/png" })
                        .setBody(Buffer().write(if(url.encodedPath.contains("preview")) png else formats.getValue(ext)))
                }
                if(url.encodedPath.endsWith("/tag.json") || url.encodedPath.endsWith("/tags.json") || url.queryParameter("s")=="tag") {
                    val board=request.getHeader("X-Fixture-Board") ?: "konachan"
                    val tags=JSONArray(listOf("blue_hair","blue_$board").mapIndexed { index,name -> JSONObject()
                        .put("id",index+1).put("name",name).put("count",1000-index).put("post_count",1000-index).put("type",0).put("category",0) })
                    return MockResponse().setHeader("Content-Type","application/json")
                        .setBody(if(board=="gelbooru") JSONObject().put("tag",tags).toString() else tags.toString())
                }
                val gel=url.encodedPath.contains("index.php"); val dan=url.encodedPath.endsWith("posts.json")
                val page=if(gel) (url.queryParameter("pid")?.toInt() ?: 0)+1 else url.queryParameter("page")?.toInt() ?: 1
                val count=if(page>pageCount) 0 else if(page>1) 1 else url.queryParameter("limit")?.toInt() ?: 20
                val rows=JSONArray((0 until count).map { n ->
                    val id=(page-1)*20+n+1; val ext=if(onlyStatic.get()) "png" else listOf("png","gif","mp4","webm")[n%4]
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
        previewGate.countDown()
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
        assertEquals(setOf(SearchMode.DESCRIPTION),caps.suggestionModes)
        assertTrue(caps.facets.isEmpty()); assertFalse(caps.sequence); assertFalse(caps.random)
        assertEquals(MoeviewCategories.forBoard(row.config.board),caps.categories)
        for (board in Board.entries) {
            val scoped=profile(MoeviewConfig(board=board))
            assertFalse(board.label,SourceWire.capabilities(client.request(scoped.id,ExtensionProtocol.CAPABILITIES,payload(scoped))).random)
        }
        assertEquals(0,server.requestCount)
    }
    @Test fun boardDefaultsUseReviewedNonemptyGeneralAndCharacterTagsWithoutFetchingDuringDiscovery(): Unit=runBlocking {
        val reviewed=JSONObject(instrumentation.context.assets.open("category-tags.json").bufferedReader().use { it.readText() }).getJSONObject("boards")
        for(board in Board.entries) {
            val row=profile(MoeviewConfig(board=board))
            val categories=SourceWire.capabilities(client.request(row.id,ExtensionProtocol.CAPABILITIES,payload(row))).categories
            assertTrue(categories.size in 2..40)
            assertEquals("all",categories.first().id); assertEquals("",categories.first().query.text)
            assertEquals(categories.size,categories.map { it.query.text }.distinct().size)
            val vocabulary=reviewed.getJSONObject(board.name)
            categories.drop(1).forEach { category ->
                val tag=vocabulary.getJSONObject(category.query.text)
                assertTrue(tag.getInt("type") in setOf(0,4)); assertTrue(tag.getBoolean("nonempty"))
                if(board!=Board.DANBOORU) assertTrue(tag.getBoolean("liveSafeResult"))
                assertEquals(SearchMode.DESCRIPTION,category.query.mode)
            }
        }
        // Familiar strings can name unrelated artists/circles instead of expressions.
        val yande=MoeviewCategories.forBoard(Board.YANDERE).map { it.query.text }
        assertFalse(yande.any { it in setOf("blush","smile","ponytail","hug","happy","deletethistag") })
        assertEquals(0,server.requestCount)
    }
    private suspend fun suggestions(row: MoeviewProfile,text: String,cursor: Int=text.length,mode: SearchMode=SearchMode.DESCRIPTION)=
        SourceWire.suggestions(client.request(row.id,ExtensionProtocol.SUGGESTIONS,payload(row)
            .put("query",SourceWire.query(MediaQuery(text,mode=mode))).put("cursor",cursor))).validate(text,cursor)
    @Test fun boardSuggestionsUseTheRightEndpointAndReplaceOnlyTheCurrentToken(): Unit=runBlocking {
        for(board in Board.entries) {
            val row=profile(MoeviewConfig(board=board))
            val query="smile -blu later"; val cursor=query.indexOf(" later")
            val found=suggestions(row,query,cursor)
            assertEquals(6,found.start); assertEquals(cursor,found.end)
            assertEquals(listOf("blue_hair","blue_${board.id}"),found.items.map { it.label })
            assertEquals("smile -blue_hair later",query.replaceRange(found.start,found.end,found.items.first().value))
            val request=server.takeRequest().requestUrl!!
            val key=when(board) { Board.DANBOORU -> "search[name_matches]"; Board.GELBOORU -> "name_pattern"; else -> "name" }
            assertEquals("blu"+if(board==Board.GELBOORU) "%" else "*",request.queryParameter(key))
        }
    }
    @Test fun shortPrefixesUnsupportedModesAndMetatagsNeverMakeAnHttpCall(): Unit=runBlocking {
        val row=profile()
        for(text in listOf("b","smile b","rating:s","blue*","smile ")) assertTrue(suggestions(row,text).items.isEmpty())
        assertTrue(suggestions(row,"blue",mode=SearchMode.CONTEXT).items.isEmpty())
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
    @Test fun gelbooruUsesZeroBasedPagingAndNormalizesUnsupportedRandomWithoutBreakingContinuation(): Unit=runBlocking {
        val row=profile(MoeviewConfig(board=Board.GELBOORU))
        val first=page(row); assertEquals("2026-10-18T09:00:35Z",first.items.first().remote!!.uploadedAt)
        assertEquals("0",server.takeRequest().requestUrl!!.queryParameter("pid"))
        val normalized=page(row,MediaQuery(randomRequest="obsolete-random")); assertNotNull(normalized.nextCursor)
        assertFalse(server.takeRequest().requestUrl!!.queryParameter("tags")!!.contains("random"))
        page(row,cursor=normalized.nextCursor); assertEquals("1",server.takeRequest().requestUrl!!.queryParameter("pid"))
        assertEquals(3,server.requestCount)
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
    private fun clickScrolled(id: String) {
        waitFor("Visible $id") {
            val all=nodes(); val control=all.firstOrNull { it.viewIdResourceName=="one.njk.sao:id/$id" }
            if(control?.isVisibleToUser==true) true else {
                if(control!=null) control.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                else all.firstOrNull { it.isScrollable && it.packageName?.toString()=="one.njk.sao" }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
        }
        click { it.viewIdResourceName=="one.njk.sao:id/$id" }
    }
    private fun input(id: String,text: String) {
        clickScrolled(id)
        val node=nodes().first { it.viewIdResourceName=="one.njk.sao:id/$id" }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
        }))
        waitFor("Cursor at end of $id") {
            val updated=nodes().firstOrNull { it.viewIdResourceName=="one.njk.sao:id/$id" && it.text?.toString()==text }
            if(updated==null) false
            else if(updated.textSelectionStart==text.length && updated.textSelectionEnd==text.length) true
            else updated.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,text.length)
            })
        }
    }
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
            waitFor("Moeview focal card and extension presets") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/media_title" && it.text?.toString()=="Konachan · 1" } && nodes().any { it.text?.toString()=="School uniform" } }
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
    @Test fun saoUsesBoardSuggestionsInFloatingSearchAndItsOwnHomeCategoryEditor(): Unit=runBlocking {
        val name="Moeview tags "+UUID.randomUUID().toString().take(6)
        profile(name=name)
        app.startActivity(app.packageManager.getLaunchIntentForPackage("one.njk.sao")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("SAO home") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/type" } }
        click { it.viewIdResourceName=="one.njk.sao:id/edit" }
        waitFor("New profile discovered") { nodes().any { it.text?.toString()==name } }
        clickText(name)
        waitFor("Profile selected") { nodes().any { it.contentDescription?.toString()=="$name, selected" } }
        click { it.viewIdResourceName=="one.njk.sao:id/back" }
        click { it.viewIdResourceName=="one.njk.sao:id/settings" }
        clickScrolled("home_categories")
        waitFor("Host category defaults") { nodes().any { it.text?.toString()=="School uniform" } }
        clickScrolled("add_category")
        input("query","blue_ha")
        waitFor("Board tag suggestions in host category editor") { nodes().any { it.text?.toString()=="blue_hair" } }
        visual("category-tag-suggestions")
        clickText("blue_hair")
        waitFor("Canonical tag inserted") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/query" && it.text?.toString()?.trim()=="blue_hair" } }
        if(instrumentation.uiAutomation.windows.any { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        clickScrolled("save_category")
        waitFor("SAO saved custom category") { nodes().any { it.text?.toString()=="blue hair" } }
        visual("custom-home-categories")
        clickScrolled("reset_categories")
        waitFor("Defaults restored") { nodes().none { it.text?.toString()=="blue hair" } }
        clickScrolled("back")
        clickScrolled("preview")
        waitFor("Floating picker") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/search_toggle" } }
        // Floating selection is deliberately independent of the home source chosen above.
        click { it.viewIdResourceName=="one.njk.sao:id/source_toggle" }
        waitFor("Floating source choice") {
            val all=nodes()
            if (all.any { it.text?.toString()==name }) true else {
                all.firstOrNull { it.viewIdResourceName=="one.njk.sao:id/source_list" && it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
        }
        clickText(name)
        waitFor("Floating source selected") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/source_toggle" && it.text?.toString()?.startsWith(name)==true } }
        click { it.viewIdResourceName=="one.njk.sao:id/search_toggle" }
        assertTrue("Unsupported Lucky action stays hidden",nodes().none { it.viewIdResourceName=="one.njk.sao:id/search_lucky" && it.isVisibleToUser })
        input("search","smile blu")
        waitFor("Board tag suggestions in floating picker") { nodes().any { it.text?.toString()=="blue_hair" } }
        visual("floating-tag-suggestions")
        clickText("blue_hair")
        waitFor("Floating completion preserves preceding tag") { nodes().any {
            it.viewIdResourceName=="one.njk.sao:id/search" && it.text?.toString()?.trim()=="smile blue_hair"
        } }
        click { it.viewIdResourceName=="one.njk.sao:id/close" }
    }
    @Test fun saoHomeSharesPreviewTransfersAndKeepsPlaceholdersInsideCards(): Unit=runBlocking {
        onlyStatic.set(true); holdPreviews.set(true)
        val name="Preview fixture "+UUID.randomUUID().toString().take(6)
        profile(name=name)
        try {
            app.startActivity(app.packageManager.getLaunchIntentForPackage("one.njk.sao")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("SAO home") { nodes().any { it.viewIdResourceName=="one.njk.sao:id/type" } }
            click { it.viewIdResourceName=="one.njk.sao:id/edit" }
            waitFor("Preview source discovered") { nodes().any { it.text?.toString()==name } }
            clickText(name)
            waitFor("Preview source selected") { nodes().any { it.contentDescription?.toString()=="$name, selected" } }
            click { it.viewIdResourceName=="one.njk.sao:id/back" }
            waitFor("Carousel and card title precede slow image bytes") {
                nodes().any { it.viewIdResourceName=="one.njk.sao:id/media_title" && it.text?.toString()=="Konachan · 1" } &&
                    paths.any { it.contains("/media/") }
            }
            val pending=paths.filter { it.contains("/media/") }
            assertTrue("Shared host transfer limit",pending.size<=3)
            assertEquals("No duplicate visible/prefetch downloads",pending.size,pending.distinct().size)
            assertTrue(pending.all { it.contains("/display-") })
            assertFalse(nodes().any { it.viewIdResourceName=="one.njk.sao:id/home_status" && it.isVisibleToUser })
            visual("home-card-previews")
            holdPreviews.set(false); previewGate.countDown()
            waitFor("First four display previews fetched") { (1..4).all { id -> paths.any { it.endsWith("/display-$id.png") } } }
            assertFalse("No original downloads for prefetch",paths.any { it.contains("/media/fixture-") })
        } finally { holdPreviews.set(false); previewGate.countDown() }
    }
}
