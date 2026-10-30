package one.njk.sao.floating

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
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
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlin.math.roundToInt

/** Synthetic data only. Never connects to a user's media server. */
class SourceHomeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
    private fun profile(name: String) = SourceProfile(UUID.randomUUID().toString(), name,
        ExtensionConfig("fixture.source","fixture.source.Service",name,"1","Fixture"))

    @Test fun homeSharingPreservesDisplayedBytesAndMimeWithoutRequestingOriginals(): Unit=runBlocking {
        val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val jpeg=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it); bitmap.recycle(); it.toByteArray() }
        val gif=instrumentation.context.assets.open("floating/hello.gif").use { it.readBytes() }
        val video=instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }
        val preview=java.io.File.createTempFile("display-fixture",null,app.cacheDir)
        var originalReads=0
        var videoReads=0
        val source=object: MediaSource {
            override val identity=kotlinx.coroutines.flow.flowOf(LOCAL_SOURCE_ID)
            override suspend fun owns(item: MediaItem)=true
            override suspend fun page(query: MediaQuery,cursor: String?)=MediaPage(emptyList())
            override suspend fun filters()=emptyList<String>()
            override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(Preview.Local(preview))
            override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                block: suspend (MediaHandle)->T): T = when(kind) {
                Rendition.PLAYBACK -> block(MediaHandle.Local(preview).also { videoReads++ })
                Rendition.ORIGINAL -> block(MediaHandle.Stream(jpeg.inputStream().also { originalReads++ }))
                Rendition.STILL -> error("Share must not flatten the displayed rendition")
            }
        }
        val exports=MediaExports(app,source)
        try {
            for((mime,bytes) in listOf("image/png" to jpeg,"image/gif" to gif,"video/mp4" to video)) {
                preview.writeBytes(bytes)
                val item=MediaItem("display_${UUID.randomUUID().toString().replace("-","")}","Display fixture","",mime,emptyList(),emptyList(),animated=mime=="image/gif")
                val shared=exports.displayed(item)
                try {
                    assertEquals(if(mime=="image/png") "image/jpeg" else mime,shared.mimeType)
                    assertEquals(shared.mimeType,app.contentResolver.getType(shared.uri))
                    assertArrayEquals(bytes,app.contentResolver.openInputStream(shared.uri)!!.use { it.readBytes() })
                    assertEquals("Share must never request the original",0,originalReads)
                    if(!item.isVideo) assertEquals(0,videoReads)
                    val download=exports.prepare(item,ExportRendition.MEDIA)
                    try { assertEquals(1,originalReads); assertArrayEquals(jpeg,download.file.readBytes()) }
                    finally { download.file.delete(); originalReads=0 }
                } finally { shared.file.delete() }
            }
            assertEquals(1,videoReads)
        } finally { preview.delete() }
    }

    @Test fun ordinaryPhotosKeepOriginalPixelsAlphaMimeAndBytesWhileStickersRemain512(): Unit = runBlocking {
        for(mime in listOf("image/jpeg","image/png")) {
            val bitmap = Bitmap.createBitmap(3000,1800,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if(mime=="image/png") Color.TRANSPARENT else Color.rgb(26,87,163))
            bitmap.setPixel(1500,900,Color.RED)
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(if(mime=="image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,97,out)
                out.toByteArray()
            }
            bitmap.recycle()
            val item = MediaItem("original_${UUID.randomUUID().toString().replace("-", "")}","Custom title","",mime,emptyList(),emptyList(),
                filename="003889-photo_1075@08-08-2023_20-38-02.${if(mime=="image/png") "png" else "jpg"}")
            var rasters = 0
            val source = object : MediaSource {
                override val identity = kotlinx.coroutines.flow.flowOf(LOCAL_SOURCE_ID)
                override suspend fun owns(item: MediaItem) = true
                override suspend fun page(query: MediaQuery,cursor: String?) = MediaPage(listOf(item))
                override suspend fun filters() = emptyList<String>()
                override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(error("Preview cannot be used for an export"))
                override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
                    block: suspend (MediaHandle) -> T): T = when (kind) {
                    Rendition.ORIGINAL -> block(MediaHandle.Stream(bytes.inputStream()))
                    Rendition.STILL -> block(MediaHandle.Stream(bytes.inputStream().also { rasters++ }))
                    Rendition.PLAYBACK -> error("A still photo has no playback rendition")
                }
            }
            val exports = MediaExports(app,source)
            val media = exports.prepare(item,ExportRendition.MEDIA)
            try {
                assertEquals(mime,media.mimeType); assertEquals(mime,app.contentResolver.getType(media.uri))
                assertEquals(0,rasters)
                assertArrayEquals(bytes,app.contentResolver.openInputStream(media.uri)!!.use { it.readBytes() })
                val decoded = BitmapFactory.decodeFile(media.file.path)
                assertEquals(3000,decoded.width); assertEquals(1800,decoded.height)
                if(mime=="image/png") assertEquals(0,Color.alpha(decoded.getPixel(0,0)))
                decoded.recycle()
                val sticker = exports.prepare(item,ExportRendition.STICKER_PNG)
                try {
                    assertEquals(1,rasters)
                    BitmapFactory.decodeFile(sticker.file.path).also {
                        assertEquals(512,it.width); assertEquals(512,it.height)
                        assertEquals(0,Color.alpha(it.getPixel(0,0))); it.recycle()
                    }
                } finally { sticker.file.delete() }
            } finally { media.file.delete() }
        }
    }

    @Test fun categoriesAndFilenamesRoundTripWithoutBreakingOlderProviders() {
        val caps = SourceCapabilities(setOf(SearchMode.CONTEXT),categories=listOf(
            SourceCategory("happy","Happy",MediaQuery(text="happy smiling"))))
        assertEquals(caps,SourceWire.capabilities(SourceWire.capabilities(caps)))
        assertTrue(SourceWire.capabilities(JSONObject().put("modes",JSONArray().put("CONTEXT"))).categories.isEmpty())
        val item = MediaItem("test","Edited title","","image/png",emptyList(),emptyList(),filename="actual.png")
        assertEquals(item,SourceWire.item(SourceWire.item(item)))
        val old = SourceWire.item(item).apply { remove("filename") }
        assertNull(SourceWire.item(old).filename)
    }

    @Test fun allRoutesIdenticalAssetIdsIndependentlyAndPaginatesEveryProfileWithoutGaps(): Unit = runBlocking {
        val members = (1..7).map { profile("Library $it") }
        val calls = java.util.concurrent.ConcurrentHashMap<String,Int>(); val closed = mutableSetOf<String>()
        val active = AtomicInteger(); val peak = AtomicInteger()
        val factory = object : MediaSourceFactory {
            override fun create(profile: SourceProfile) = object : ManagedMediaSource {
                override suspend fun page(query: MediaQuery,cursor: String?): MediaPage {
                    val n=active.incrementAndGet(); peak.updateAndGet { maxOf(it,n) }
                    try {
                        delay(20); calls[profile.id]=(calls[profile.id] ?: 0)+1
                        val offset=cursor?.toInt() ?: 0
                        return MediaPage((offset until offset+2).map { number ->
                            MediaItem("fixture_${digest(profile.id).take(8)}_$number","${profile.name} $number","","image/png",emptyList(),emptyList(),
                                remote=RemoteMedia(profile.identity,number.toString(),false),filename="$number.png")
                        }, (offset+2).toString().takeIf { offset<4 })
                    } finally { active.decrementAndGet() }
                }
                override suspend fun filters()=emptyList<String>()
                override val identity=flowOf(profile.identity)
                override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(textPreview(profile.name))
                override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                    block: suspend (MediaHandle)->T): T=block(MediaHandle.Stream(profile.name.byteInputStream()))
                override fun close() { closed+=profile.id }
            }
        }
        val all=AllMediaSource(SourceCatalog(members,members.first().id).withAllSource(true).profiles.last(),
            mapOf("extension" to Provider { factory }))
        try {
            assertTrue(calls.isEmpty())
            val items=mutableListOf<MediaItem>(); var cursor: String?=null
            do {
                val page=all.page(MediaQuery(text="same query"),cursor)
                assertFalse(page.interrupted); items+=page.items; cursor=page.nextCursor
            } while(cursor!=null)
            assertEquals(42,items.size); assertEquals(42,items.map { it.identityKey }.distinct().size)
            assertEquals(7,calls.size); assertTrue(calls.values.all { it==3 }); assertTrue(peak.get()<=3)
            for(member in members) {
                val item=items.first { it.remote?.sourceId==member.identity }
                assertTrue(all.owns(item)); all.withPreview(item) { assertEquals(member.name,it.text()) }
                assertEquals(member.name,all.withRendition(item,Rendition.ORIGINAL) { handle ->
                    handle.openStream().use { it.readBytes().toString(Charsets.UTF_8) } })
            }
            assertFalse(all.owns(items.first().copy(remote=RemoteMedia("other","0",false))))
        } finally { all.close() }
        assertEquals(members.map { it.id }.toSet(),closed)
    }

    @Test fun partialFailureRetainsSuccessfulProgressAndOnlyRetriesWhenRequested(): Unit = runBlocking {
        val members=listOf(profile("Good"),profile("Offline"))
        var online=false; var failedCalls=0; var goodCalls=0
        val factory=object : MediaSourceFactory {
            override fun create(profile: SourceProfile)=object : ManagedMediaSource {
                override suspend fun page(query: MediaQuery,cursor: String?): MediaPage {
                    if(profile.name=="Offline") { failedCalls++; if(!online) error("Fixture offline") }
                    else goodCalls++
                    return MediaPage(listOf(MediaItem("fixture_${digest(profile.id).take(8)}",profile.name,"","image/png",emptyList(),emptyList(),
                        remote=RemoteMedia(profile.identity,"same-asset-id",false))))
                }
                override suspend fun filters()=emptyList<String>()
                override val identity=flowOf(profile.identity)
                override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(textPreview(profile.name))
                override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                    block: suspend (MediaHandle)->T): T=block(MediaHandle.Stream(profile.name.byteInputStream()))
                override fun close() { }
            }
        }
        val all=AllMediaSource(SourceCatalog(members).withAllSource(true).profiles.last(),mapOf("extension" to Provider { factory }))
        try {
            val page=all.page(MediaQuery())
            assertTrue(page.interrupted); assertEquals(listOf("Good"),page.items.map { it.title }); assertNotNull(page.nextCursor)
            delay(150); assertEquals(1,failedCalls); assertEquals(1,goodCalls)
            online=true
            val retry=all.page(MediaQuery(),page.nextCursor)
            assertFalse(retry.interrupted); assertEquals(listOf("Offline"),retry.items.map { it.title })
            assertNull(retry.nextCursor); assertEquals(2,failedCalls); assertEquals(1,goodCalls)
            try { all.page(MediaQuery(text="changed"),page.nextCursor); fail("Stale cursor must be rejected") }
            catch(_: IllegalArgumentException) { }
        } finally { all.close() }
    }

    @Test fun allCapabilityAndObserverSetupShareTheBudgetWithoutBlockingLaterPages(): Unit = runBlocking {
        val members=(1..12).map { profile("Observed $it") }
        val active=AtomicInteger(); val peak=AtomicInteger(); val observers=AtomicInteger()
        val factory=object : MediaSourceFactory {
            override fun create(profile: SourceProfile)=object : ManagedMediaSource {
                private val mutex=kotlinx.coroutines.sync.Mutex()
                private var ready=false
                private suspend fun load() {
                    mutex.lock()
                    try {
                        if(!ready) {
                            val count=active.incrementAndGet(); peak.updateAndGet { maxOf(it,count) }
                            try { delay(20); ready=true } finally { active.decrementAndGet() }
                        }
                    } finally { mutex.unlock() }
                }
                override val capabilities=flow {
                    load(); emit(SourceCapabilities(setOf(SearchMode.FILENAME),changesUri="content://fixture/${profile.id}"))
                    awaitCancellation()
                }
                override val changes=flow { load(); observers.incrementAndGet(); emit(Unit); awaitCancellation() }
                override suspend fun page(query: MediaQuery,cursor: String?): MediaPage { load(); return MediaPage(emptyList()) }
                override suspend fun filters()=emptyList<String>()
                override val identity=flowOf(profile.identity)
                override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(error("Unused"))
                override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                    block: suspend (MediaHandle)->T): T=error("Unused")
                override fun close() { }
            }
        }
        val all=AllMediaSource(SourceCatalog(members).withAllSource(true).profiles.last(),mapOf("extension" to Provider { factory }))
        val caps=launch { all.capabilities.collect { } }; val updates=launch { all.changes.collect { } }
        try {
            withTimeout(3000) { while(observers.get()!=12) delay(10) }
            assertTrue("Observer setup must not bypass the three-request budget",peak.get()<=3)
            withTimeout(1000) { all.page(MediaQuery()) }
        } finally { caps.cancelAndJoin(); updates.cancelAndJoin(); all.close() }
    }

    @Test fun allRejectsCyclingChildCursorsInsteadOfFetchingForever(): Unit = runBlocking {
        val member=profile("Cycling source")
        val factory=object : MediaSourceFactory {
            override fun create(profile: SourceProfile)=object : ManagedMediaSource {
                override suspend fun page(query: MediaQuery,cursor: String?) = MediaPage(listOf(
                    MediaItem("item_${cursor ?: "0"}","Cursor fixture","","image/png",emptyList(),emptyList())),
                    when(cursor) { null -> "2"; "2" -> "3"; else -> "2" })
                override suspend fun filters()=emptyList<String>()
                override val identity=flowOf(profile.identity)
                override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(error("Unused"))
                override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                    block: suspend (MediaHandle)->T): T=error("Unused")
                override fun close() { }
            }
        }
        val all=AllMediaSource(SourceCatalog(listOf(member)).withAllSource(true).profiles.last(),mapOf("extension" to Provider { factory }))
        try {
            val first=all.page(MediaQuery()); val second=all.page(MediaQuery(),first.nextCursor)
            val third=all.page(MediaQuery(),second.nextCursor)
            assertTrue(third.interrupted); assertTrue(third.items.isEmpty()); assertNotNull(third.nextCursor)
        } finally { all.close() }
    }

    @Test fun routerUsesOriginalOwnerInAllAndRejectsItAfterSwitching(): Unit = runBlocking {
        val profiles=access.sources(); val previous=profiles.current()
        val members=listOf(profile("First"),profile("Second"))
        val factory=object : MediaSourceFactory {
            override fun create(profile: SourceProfile)=object : ManagedMediaSource {
                override val capabilities=flowOf(SourceCapabilities(setOf(SearchMode.FILENAME),
                    setOf(SearchFacet.MEDIA,SearchFacet.PEOPLE),sequence=true,
                    categories=listOf(SourceCategory("all","All"),SourceCategory(profile.id,profile.name))))
                override suspend fun page(query: MediaQuery,cursor: String?)=MediaPage(listOf(
                    MediaItem("fixture_${digest(profile.id).take(8)}",profile.name,"","image/png",emptyList(),emptyList(),
                        remote=RemoteMedia(profile.identity,"same",false))))
                override suspend fun filters()=emptyList<String>()
                override val identity=flowOf(profile.identity)
                override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(textPreview(profile.name))
                override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                    block: suspend (MediaHandle)->T): T=block(MediaHandle.Stream(profile.name.byteInputStream()))
                override fun close() { }
            }
        }
        val router=SwitchingMediaSource(profiles,mapOf("extension" to Provider { factory }),FloatingDispatchers())
        try {
            profiles.restore(SourceCatalog()); members.forEach { profiles.upsert(it) }; profiles.available(members.map { it.id }.toSet())
            profiles.enableAll(true); profiles.select(ALL_SOURCE_ID)
            val caps=router.capabilities.first { it.sourceId==profiles.current().identity && it.categories.isNotEmpty() }
            assertEquals(setOf(SearchMode.FILENAME),caps.modes); assertEquals(3,caps.categories.size)
            assertFalse(SearchFacet.PEOPLE in caps.facets)
            val items=router.page(MediaQuery()).items
            assertEquals(2,items.size)
            for(item in items) { assertTrue(router.owns(item)); assertEquals(item.title,
                router.withRendition(item,Rendition.ORIGINAL) { handle ->
                    handle.openStream().use { it.readBytes().toString(Charsets.UTF_8) } }) }
            val first=items.first { it.remote?.sourceId==members.first().identity }
            profiles.select(members.last().id)
            assertFalse(router.owns(first))
            try { router.withRendition(first,Rendition.ORIGINAL) { }; fail("Old All item must not route into the newly selected profile") }
            catch(_: IllegalStateException) { }
        } finally { router.shutdown(); profiles.restore(previous) }
    }

    @Test fun allPreferencePersistsAndDisablingItSelectsARealSource(): Unit = runBlocking {
        val profiles=access.sources(); val previous=profiles.current()
        val members=listOf(profile("One"),profile("Two"))
        try {
            profiles.restore(SourceCatalog()); members.forEach { profiles.upsert(it) }; profiles.available(members.map { it.id }.toSet())
            profiles.enableAll(true); profiles.select(ALL_SOURCE_ID)
            assertEquals(2,(profiles.current().selected!!.configuration as AllSourceConfiguration).members.size)
            val fresh=SourceProfiles(app,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
            assertEquals(ALL_SOURCE_ID,fresh.current().selectedId)
            profiles.enableAll(false)
            assertEquals(members.first().id,profiles.current().selectedId)
            assertFalse(profiles.current().profiles.any { it.id==ALL_SOURCE_ID })
        } finally { profiles.restore(previous) }
    }

    @Test fun homeUsesExtensionPresetsOriginalLayoutFocalSelectionAndRetainsFocusOnRecreation(): Unit = runBlocking {
        val profiles=access.sources(); val previous=profiles.current()
        val searches=AtomicInteger()
        val originalRequests=AtomicInteger()
        val thumbnailRequests=AtomicInteger()
        val png=instrumentation.context.assets.open("floating/happy.png").use { it.readBytes() }
        val gif=instrumentation.context.assets.open("floating/hello.gif").use { it.readBytes() }
        val video=instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }
        var lastQuery=""
        val delayOriginal=java.util.concurrent.atomic.AtomicBoolean()
        val originalStarted=java.util.concurrent.atomic.AtomicBoolean()
        val server=MockWebServer().apply {
            dispatcher=object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path=="/api/server/features" -> MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                    request.path=="/api/tags" -> MockResponse().setBody("[]")
                    request.path?.contains("/thumbnail")==true -> { if(request.path!!.contains("000000000000")) thumbnailRequests.incrementAndGet(); MockResponse().setBody(Buffer().write(png)) }
                    (request.path?.contains("/original")==true || request.path?.contains("/video/playback")==true) -> MockResponse().setBody(Buffer().write(
                        if(request.path!!.contains("000000000001")) gif else if(request.path!!.contains("000000000002")) video else png)).also { response ->
                        if(request.path!!.contains("000000000000")) originalRequests.incrementAndGet()
                        if(delayOriginal.get() && request.path!!.contains("000000000003")) {
                            originalStarted.set(true); response.setBodyDelay(1,java.util.concurrent.TimeUnit.SECONDS)
                        }
                    }
                    else -> {
                        searches.incrementAndGet()
                        val body=JSONObject(request.body.readUtf8())
                        lastQuery=body.optString("query")
                        val items=JSONArray((0 until 24).map { index -> JSONObject()
                            .put("id",UUID(42,index.toLong()).toString()).put("type",if(index==2) "VIDEO" else "IMAGE")
                            .put("originalMimeType",when(index) { 1 -> "image/gif"; 2 -> "video/mp4"; else -> "image/png" })
                            .put("originalFileName","fixture-$index.${when(index) { 1 -> "gif"; 2 -> "mp4"; else -> "png" }}")
                            .put("exifInfo",JSONObject().put("description","Source image $index"))
                            .put("updatedAt","2026-10-18T00:00:00Z") })
                        MockResponse().setBody(JSONObject().put("assets",JSONObject().put("items",items)).toString())
                    }
                }
            }; start()
        }
        try {
            profiles.restore(SourceCatalog())
            profiles.upsert(immichProfile("Home fixture",ImmichConfig(server.url("/").toString(),"home-fixture-key")),true)
            val alternate=immichProfile("Home alternate",ImmichConfig(server.url("/").toString(),"home-alternate-key"))
            profiles.upsert(alternate,false)
            ActivityScenario.launch<MainActivity>(IntentForHome.create(app)).use { scenario ->
                lateinit var model: ArtsViewModel
                scenario.onActivity { model=ViewModelProvider(it)[ArtsViewModel::class.java] }
                await(scenario,"Home focal card") { model.focusedId!=null && it.findViewById<ChipGroup>(R.id.categories).childCount>=8 }
                assertEquals(24,model.state.value.browse.items.size)
                assertEquals("Collection: Home fixture", text(scenario,R.id.type))
                scenario.onActivity { activity ->
                    val grid=activity.findViewById<RecyclerView>(R.id.carousel_recycler_view)
                    assertTrue(grid.adapter is MediaCarouselAdapter)
                    assertEquals(450,(grid.height/activity.resources.displayMetrics.density).roundToInt())
                    activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
                    activity.findViewById<View>(R.id.media_title).performLongClick()
                }
                await(scenario,"Home filename copied") { it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()=="fixture-0.png" }
                fun home(activity: MainActivity): one.njk.sao.CarouselFragment {
                    fun walk(manager: androidx.fragment.app.FragmentManager): one.njk.sao.CarouselFragment? =
                        manager.fragments.firstNotNullOfOrNull { fragment ->
                            fragment as? one.njk.sao.CarouselFragment ?: walk(fragment.childFragmentManager)
                        }
                    return requireNotNull(walk(activity.supportFragmentManager))
                }
                pauseSourceVisual("source-home")
                val shared=java.util.concurrent.atomic.AtomicReference<android.content.Intent?>()
                val monitor=object: android.app.Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                        if(intent.action!=android.content.Intent.ACTION_CHOOSER) return null
                        shared.set(intent.getParcelableExtra(android.content.Intent.EXTRA_INTENT,android.content.Intent::class.java))
                        return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
                    }
                }
                instrumentation.addMonitor(monitor)
                try {
                    await(scenario,"The focal preview has been requested") { thumbnailRequests.get()>0 }
                    val previewsBefore=thumbnailRequests.get()
                    scenario.onActivity { it.findViewById<View>(R.id.share).performClick() }
                    await(scenario,"Home Share opens the chooser") { shared.get()!=null }
                    val intent=shared.get()!!
                    assertEquals("image/png",intent.type)
                    assertArrayEquals(png,app.contentResolver.openInputStream(intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM,android.net.Uri::class.java)!!)!!.use { it.readBytes() })
                    assertEquals("Share must reuse the cached preview",previewsBefore,thumbnailRequests.get())
                    assertEquals("Share must not request the original",0,originalRequests.get())
                } finally { instrumentation.removeMonitor(monitor) }
                scenario.onActivity { activity ->
                    // Follow the real side-card tap route, including its smooth scroll callbacks.
                    activity.findViewById<RecyclerView>(R.id.carousel_recycler_view).findViewHolderForAdapterPosition(1)!!.itemView.performClick()
                }
                await(scenario,"Home focused GIF animates") { activity ->
                    val holder=activity.findViewById<RecyclerView>(R.id.carousel_recycler_view).findViewHolderForAdapterPosition(1) as? MediaCarouselAdapter.Holder
                    (holder?.binding?.preview?.drawable as? Animatable)?.isRunning==true
                }
                var gifCallback: android.graphics.drawable.Drawable.Callback?=null
                var gifDrawable: android.graphics.drawable.Drawable?=null
                pauseSourceVisual("source-home-gif-before-open")
                scenario.onActivity { activity ->
                    val grid=activity.findViewById<RecyclerView>(R.id.carousel_recycler_view)
                    val holder=grid.findViewHolderForAdapterPosition(1) as MediaCarouselAdapter.Holder
                    gifDrawable=holder.binding.preview.drawable; gifCallback=gifDrawable?.callback
                    holder.itemView.performLongClick()
                }
                await(scenario,"The full-size animated GIF renders") { activity ->
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as? MediaViewerFragment
                    val root=viewer?.view
                    root?.findViewById<View>(R.id.media_viewer_progress)?.visibility==View.GONE &&
                        root.findViewById<View>(R.id.media_viewer_retry).visibility==View.GONE
                }
                scenario.onActivity { activity ->
                    assertSame("The viewer must not steal an animated thumbnail callback",gifCallback,gifDrawable?.callback)
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as MediaViewerFragment
                    assertEquals("The live animation hierarchy must not be scaled",1f,viewer.requireView().scaleX,0f)
                    assertEquals(1f,viewer.requireView().scaleY,0f)
                    viewer.dismiss()
                }
                await(scenario,"Closing the GIF viewer resumes the original animated card") {
                    (gifDrawable as? Animatable)?.isRunning==true && gifDrawable?.callback===gifCallback
                }
                scenario.onActivity { it.findViewById<RecyclerView>(R.id.carousel_recycler_view).smoothScrollToPosition(2) }
                await(scenario,"Home video plays muted") { activity ->
                    val holder=activity.findViewById<RecyclerView>(R.id.carousel_recycler_view).findViewHolderForAdapterPosition(2) as? MediaCarouselAdapter.Holder
                    val player=holder?.binding?.videoPreview?.player
                    player?.isPlaying==true && player.volume==0f
                }
                pauseSourceVisual("source-home-video")
                scenario.onActivity { activity -> activity.findViewById<RecyclerView>(R.id.carousel_recycler_view)
                    .findViewHolderForAdapterPosition(2)!!.itemView.performLongClick() }
                await(scenario,"Original video streams in the full-size viewer") { activity ->
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as? MediaViewerFragment
                    viewer?.view?.findViewById<androidx.media3.ui.PlayerView>(R.id.media_viewer_player)?.player?.isPlaying==true
                }
                scenario.onActivity { activity ->
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as MediaViewerFragment
                    val video=viewer.requireView().findViewById<androidx.media3.ui.PlayerView>(R.id.media_viewer_player)
                    assertFalse("The original-video preview must open without controls",video.isControllerFullyVisible)
                    video.performClick()
                }
                await(scenario,"Tapping the preview reveals its controls") { activity ->
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as? MediaViewerFragment
                    viewer?.view?.findViewById<androidx.media3.ui.PlayerView>(R.id.media_viewer_player)?.isControllerFullyVisible==true
                }
                scenario.onActivity { activity ->
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as MediaViewerFragment
                    val player=requireNotNull(viewer.requireView().findViewById<androidx.media3.ui.PlayerView>(R.id.media_viewer_player).player)
                    assertTrue("The full-size Media3 timeline must expose seeking",player.isCurrentMediaItemSeekable)
                    player.seekTo(player.duration/2)
                    assertTrue(player.currentPosition>0)
                    viewer.dismiss()
                }

                val wanted=model.state.value.browse.items[7].identityKey
                scenario.onActivity { it.findViewById<RecyclerView>(R.id.carousel_recycler_view).scrollToPosition(7) }
                await(scenario,"Scroll to seventh card") { model.focusedId==wanted }
                scenario.onActivity { activity ->
                    val grid=activity.findViewById<RecyclerView>(R.id.carousel_recycler_view)
                    val focal=grid.findViewHolderForAdapterPosition(7)!!.itemView as com.google.android.material.carousel.MaskableFrameLayout
                    val before=grid.findViewHolderForAdapterPosition(6)!!.itemView as com.google.android.material.carousel.MaskableFrameLayout
                    val after=grid.findViewHolderForAdapterPosition(8)!!.itemView as com.google.android.material.carousel.MaskableFrameLayout
                    assertTrue("Prominent focal card",focal.maskRectF.width()>grid.width*.75f)
                    assertTrue("Both side previews",before.maskRectF.width()>0 && after.maskRectF.width()>0)
                    assertEquals(grid.width/2f,focal.x+focal.width/2f,2f)
                }
                pauseSourceVisual("source-home-centered")
                var homeGrid: RecyclerView?=null
                val beforeViewerSearches=searches.get()
                scenario.onActivity { activity ->
                    homeGrid=activity.findViewById(R.id.carousel_recycler_view)
                    requireNotNull(homeGrid).findViewHolderForAdapterPosition(7)!!.itemView.performLongClick()
                }
                await(scenario,"Full-size viewer loads the original") { activity ->
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as? MediaViewerFragment
                    viewer?.view?.findViewById<View>(R.id.media_viewer_progress)?.visibility==View.GONE
                }
                delay(200)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { java.io.File(app.getExternalFilesDir("verification"),"home-fullsize.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                    finally { bitmap.recycle() }
                }
                scenario.onActivity { activity ->
                    val viewer=home(activity).childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG) as MediaViewerFragment
                    viewer.dismiss()
                }
                await(scenario,"Closing preview retains the original carousel") { activity ->
                    activity.findViewById<RecyclerView>(R.id.carousel_recycler_view)===homeGrid && model.focusedId==wanted
                }
                assertEquals("Preview must not restart the home search",beforeViewerSearches,searches.get())

                val count=searches.get(); scenario.recreate()
                await(scenario,"Restore focal card") { model.focusedId==wanted && it.findViewById<TextView>(R.id.media_title).text.toString()=="Source image 7" }
                assertEquals(count,searches.get())
                scenario.onActivity { activity ->
                    val chips=activity.findViewById<ChipGroup>(R.id.categories)
                    (0 until chips.childCount).map { chips.getChildAt(it) }.first { it.tag=="happy" }.performClick()
                }
                await(scenario,"Provider-defined category query") { lastQuery=="happy" && !model.state.value.browse.loading }
                pauseSourceVisual("source-home-category")
                lateinit var home: MainActivity; var x=0f; var y=0f
                val down=SystemClock.uptimeMillis()
                fun send(action: Int,dy: Float=0f) = instrumentation.runOnMainSync {
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y+dy,0)
                    home.dispatchTouchEvent(event); event.recycle()
                }
                scenario.onActivity { activity ->
                    home=activity
                    val anchor=activity.findViewById<View>(R.id.type)
                    val location=IntArray(2).also(anchor::getLocationOnScreen)
                    x=location[0]+anchor.width/2f; y=location[1]+anchor.height/2f
                }
                send(MotionEvent.ACTION_DOWN)
                await(scenario,"Home collection expands on hold") { it.findViewById<View>(R.id.hold_choice_card)!=null }
                pauseSourceVisual("source-home-collection-hold")
                var distance=0f
                scenario.onActivity { activity ->
                    val catalog=ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value.catalog
                    val choices=catalog.profiles
                    val steps=choices.size+Math.floorMod(choices.indexOfFirst { it.id==catalog.selectedId }-choices.indexOfFirst { it.id==alternate.id },choices.size)
                    distance=activity.findViewById<View>(R.id.hold_choice_highlight).height*steps.toFloat()
                }
                send(MotionEvent.ACTION_MOVE,distance) // Loop through all installed profiles before choosing.
                send(MotionEvent.ACTION_UP,distance)
                await(scenario,"Release applies the previewed collection") { it.findViewById<TextView>(R.id.type).text.toString()=="Collection: Home alternate" && !model.state.value.browse.loading }
                assertEquals(alternate.id,profiles.current().selectedId)
                scenario.onActivity { it.findViewById<View>(R.id.type).performClick() }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Home fixture"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click())
                await(scenario,"Tap retains the full collection list") { it.findViewById<TextView>(R.id.type).text.toString()=="Collection: Home fixture" && !model.state.value.browse.loading }
            }
            val preferences=access.preferences(); val oldMode=preferences.current().preferStickers
            try {
                preferences.preferStickers(false); access.browseSession().reset()
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,"nu.gpu.nagramx",null)).use { scenario ->
                    await(scenario,"Floating title from the same provider") { it.findViewById<TextView>(R.id.selection_title).text.toString()=="Source image 0" }
                    scenario.onActivity { activity ->
                        activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
                        activity.findViewById<View>(R.id.selection_title).performLongClick()
                    }
                    await(scenario,"Floating filename copied") { it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()=="fixture-0.png" }
                    scenario.onActivity { it.findViewById<View>(R.id.copy_action).performClick() }
                    await(scenario,"Telegram media clipboard uses original PNG") { activity ->
                        activity.getSystemService(ClipboardManager::class.java).primaryClipDescription?.hasMimeType("image/png")==true
                    }
                    scenario.onActivity { activity ->
                        val uri=activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).uri
                        assertArrayEquals(png,activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                    }
                    scenario.onActivity { it.findViewById<RecyclerView>(R.id.media_grid).scrollToPosition(3) }
                    await(scenario,"Center next uncached image") { it.findViewById<TextView>(R.id.selection_title).text.toString()=="Source image 3" }
                    delayOriginal.set(true)
                    scenario.onActivity { it.findViewById<View>(R.id.copy_action).performClick() }
                    withTimeout(5000) { while(!originalStarted.get()) delay(10) }
                    scenario.onActivity { it.findViewById<View>(R.id.selection_title).performLongClick() }
                    await(scenario,"Filename supersedes the pending image copy") { it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()=="fixture-3.png" }
                    delay(1300)
                    scenario.onActivity { activity ->
                        assertEquals("fixture-3.png",activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
                    }
                }
            } finally { preferences.preferStickers(oldMode); access.browseSession().reset() }
        } finally { profiles.restore(previous); server.shutdown() }
    }
    @Test fun manageSourcesExposesExperimentalAllAndHidesItsEditButton(): Unit = runBlocking {
        val profiles=access.sources(); val previous=profiles.current()
        try {
            profiles.restore(SourceCatalog())
            profiles.upsert(immichProfile("All settings fixture",ImmichConfig("http://127.0.0.1:9","offline-fixture-key")),true)
            ActivityScenario.launch<SourceSettingsActivity>(android.content.Intent(app,SourceSettingsActivity::class.java)).use { scenario ->
                await(scenario,"Sources loaded") { it.findViewById<View>(R.id.all_sources)?.isEnabled==true }
                scenario.onActivity { it.findViewById<View>(R.id.all_sources).performClick() }
                await(scenario,"All source appears") { activity ->
                    val state=ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value
                    state.allEnabled && state.catalog.profiles.any { it.id==ALL_SOURCE_ID }
                }
                scenario.onActivity { activity ->
                    val list=activity.findViewById<RecyclerView>(R.id.source_list)
                    val adapter=(list.adapter as androidx.recyclerview.widget.ConcatAdapter).adapters.filterIsInstance<SourceListAdapter>().single()
                    val position=adapter.currentList.indexOfFirst { it.id==ALL_SOURCE_ID }
                    list.scrollToPosition(position+1)
                }
                await(scenario,"All has no provider settings button") { activity ->
                    val list=activity.findViewById<RecyclerView>(R.id.source_list)
                    val adapter=(list.adapter as androidx.recyclerview.widget.ConcatAdapter).adapters.filterIsInstance<SourceListAdapter>().single()
                    val holder=list.findViewHolderForAdapterPosition(adapter.currentList.indexOfFirst { it.id==ALL_SOURCE_ID }+1) as? SourceListAdapter.Holder
                    holder!=null && holder.binding.edit.visibility==View.GONE
                }
                scenario.onActivity { it.findViewById<RecyclerView>(R.id.source_list).scrollToPosition(0) }
                await(scenario,"General controls visible again") { it.findViewById<View>(R.id.all_sources)?.isShown==true }
                scenario.onActivity { it.findViewById<View>(R.id.all_sources).performClick() }
                await(scenario,"All removed") { activity -> !ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value.allEnabled }
            }
        } finally { profiles.restore(previous) }
    }

    private fun <A: android.app.Activity> await(scenario: ActivityScenario<A>,label: String,predicate: (A)->Boolean) {
        val until=SystemClock.elapsedRealtime()+15_000; var ready=false
        while(!ready && SystemClock.elapsedRealtime()<until) { scenario.onActivity { ready=predicate(it) }; SystemClock.sleep(40) }
        assertTrue(label,ready)
    }
    private fun text(scenario: ActivityScenario<MainActivity>,id: Int): String {
        var result=""; scenario.onActivity { result=it.findViewById<TextView>(id).text.toString() }; return result
    }
    private object IntentForHome {
        fun create(context: android.content.Context)=android.content.Intent(context,MainActivity::class.java)
    }
}
