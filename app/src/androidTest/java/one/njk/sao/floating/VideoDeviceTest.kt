package one.njk.sao.floating

import one.njk.sao.source.*
import one.njk.sao.immichprovider.*

import android.content.ClipboardManager
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okio.Buffer
import one.njk.sao.R

@androidx.annotation.OptIn(UnstableApi::class)
class VideoDeviceTest {
    @get:org.junit.Rule val marquees = PickerMarqueeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private lateinit var server: MockWebServer
    private var previous = SourceCatalog()
    private lateinit var bytes: ByteArray
    private val videoId = "7da8d1d6-53d6-43e9-aed1-dbe12f5e14db"
    private val imageId = "27ebd6c5-fc12-49f3-a4b9-16d74584816d"
    private val rankedIds = listOf(videoId, imageId) + (2..7).map { "27ebd6c5-fc12-49f3-a4b9-16d74584816$it" }
    private val result = """{"assets":{"items":[{"id":"$videoId","type":"VIDEO","originalFileName":"animation.mp4","originalMimeType":"video/mp4","updatedAt":"2026-10-10T00:00:00Z","duration":2000},{"id":"$imageId","type":"IMAGE","originalFileName":"still.png","originalMimeType":"image/png","updatedAt":"2026-10-10T00:00:00Z"}""" + rankedIds.drop(2).joinToString("") { ",{\"id\":\"$it\",\"type\":\"IMAGE\",\"originalFileName\":\"still.png\",\"originalMimeType\":\"image/png\",\"updatedAt\":\"2026-10-10T00:00:00Z\"}" } + "]}}"
    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.browseSession().reset()
        bytes = instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }
        val source = LocalMediaSource(app); val sample = source.all().first { !it.animated }
        val preview = InstrumentationRegistry.getInstrumentation().context.assets.open(sample.asset).use { it.readBytes() }
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                request.path == "/api/server/features" -> MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                request.path == "/api/tags" -> MockResponse().setBody("[]")
                request.path?.contains("/thumbnail") == true -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(preview))
                request.path?.contains("/original") == true -> MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
                else -> MockResponse().setBody(result)
            }
        }
        server.start(); access.immichSettings().save(ImmichConfig(server.url("/").toString(), "video-contract-key"))
        // Non-UI tests do not open the source screen, which refreshes installed profiles.
        // Replace availability left behind by a previously closed fixture explicitly.
        access.sources().available(access.sources().current().profiles.map { it.id }.toSet())
    }
    @After fun cleanup() = runBlocking {
        access.sources().restore(previous); access.browseSession().reset(); server.shutdown()
    }
    private fun await(scenario: ActivityScenario<PickerActivity>, label: String, condition: (PickerActivity) -> Boolean) {
        var ready = false; val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = condition(it) }; SystemClock.sleep(40)
        }
        assertTrue(label, ready)
    }
    private fun holder(activity: PickerActivity) = activity.findViewById<RecyclerView>(R.id.media_grid)
        .findViewHolderForAdapterPosition(0) as? MediaCarouselAdapter.Holder
    @Test fun videoKeepsServerRankAndExportsOriginalMp4WithCorrectMime(): Unit = runBlocking {
        val source = SwitchingMediaSource(app, LocalMediaSource(app), access.immichSettings(), FloatingDispatchers())
        val page = source.page(MediaQuery("skill")); val item = page.items.first()
        assertEquals(rankedIds, page.items.map { it.remote!!.assetId })
        assertTrue(item.isVideo); assertTrue(item.hasMotion)
        assertEquals("/api/server/features", server.takeRequest()!!.path)
        val query = org.json.JSONObject(server.takeRequest()!!.body.readUtf8())
        assertEquals(1, query.getInt("page")); assertFalse(query.has("type")); assertFalse(query.has("filter"))
        val export = MediaExports(app, source).prepare(item, ExportRendition.MEDIA)
        assertTrue(export.file.name.endsWith(".mp4")); assertEquals("video/mp4", export.mimeType)
        assertEquals("video/mp4", app.contentResolver.getType(export.uri))
        assertTrue(bytes.contentEquals(export.file.readBytes()))
        assertTrue(bytes.contentEquals(app.contentResolver.openInputStream(export.uri)!!.use { it.readBytes() }))
        for (kind in listOf(ExportRendition.STICKER_WEBP, ExportRendition.STICKER_PNG)) {
            try { MediaExports(app, source).prepare(item, kind); fail("Video was flattened into a sticker") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun instagramPasteUsesVideoCapabilityAndOriginalBytesWithoutARecipientPicker() = runBlocking {
        val registry = one.njk.sao.floating.nativebridge.NativeBridgeRegistry
        val beforeActive = FloatingSession.active.value
        val preferences = FloatingPreferences(app); val before = preferences.current()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            FloatingSession.active(true); preferences.pasteAssistance(false)
            for (videoEnabled in listOf(false, true)) {
                registry.register("com.instagram.android", object : one.njk.sao.floating.nativebridge.INativeStickerClient.Stub() {
                    override fun setActive(active: Boolean, generation: Long) = Unit
                    override fun snapshot(beginSelection: Boolean) = android.os.Bundle().apply {
                        putString("session", "video-ui"); putLong("created", SystemClock.elapsedRealtime())
                        putStringArray("mime_types", if (videoEnabled) arrayOf("image/jpeg", "video/mp4") else arrayOf("image/jpeg"))
                        putBoolean("resumed", true)
                    }
                    override fun insert(uri: android.net.Uri, mimeType: String, session: String, requestId: Long): Boolean {
                        assertEquals("video/mp4", mimeType)
                        assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                        calls.incrementAndGet(); return true
                    }
                })
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.instagram.android", null, "video-ui")).use { scenario ->
                    await(scenario, "The centered video must expose exactly the host's supported action") {
                        holder(it)?.item?.isVideo == true &&
                            registry.supports("com.instagram.android", "video-ui", "image/jpeg") &&
                            it.findViewById<View>(R.id.paste_action).isEnabled == videoEnabled
                    }
                    scenario.onActivity {
                        assertFalse(it.findViewById<View>(R.id.sticker_action).isEnabled)
                        assertEquals(0, calls.get())
                        assertNull(ViewModelProvider(it)[PickerViewModel::class.java].selection.value)
                    }
                    if (videoEnabled) {
                        await(scenario, "Native-video guidance must describe the direct send") {
                            it.findViewById<android.widget.TextView>(R.id.selection_message).text.contains("video directly")
                        }
                        if (InstrumentationRegistry.getArguments().containsKey("dockShots")) {
                            SystemClock.sleep(800)
                            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                            java.io.File(app.filesDir, "instagram-video-paste.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                        scenario.onActivity { it.findViewById<View>(R.id.paste_action).performClick() }
                        kotlinx.coroutines.withTimeout(5_000) { while (calls.get() == 0) kotlinx.coroutines.delay(20) }
                        assertEquals(1, calls.get())
                    }
                }
                registry.clear()
            }
        } finally { registry.clear(); FloatingSession.active(beforeActive); preferences.pasteAssistance(before.pasteAssistance) }
    }
    @Test fun instagramVideoFailureExplainsTheActualNativeGate() = runBlocking {
        val registry = one.njk.sao.floating.nativebridge.NativeBridgeRegistry
        val beforeActive = FloatingSession.active.value
        try {
            FloatingSession.active(true)
            for ((gate, expected) in listOf("gallery_unavailable" to "could not load", "thread_unresolved" to "could not bind")) {
                registry.register("com.instagram.android", object : one.njk.sao.floating.nativebridge.INativeStickerClient.Stub() {
                    override fun setActive(active: Boolean, generation: Long) = Unit
                    override fun snapshot(beginSelection: Boolean) = android.os.Bundle().apply {
                        putString("status", "pending_metadata"); putString("host_version", "435.0.0.37.76")
                        putString("video_status", gate); putInt("bridge_revision", 1)
                    }
                    override fun insert(uri: android.net.Uri, mimeType: String, session: String, requestId: Long): Boolean {
                        fail("An unavailable video handler must not dispatch"); return false
                    }
                })
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.instagram.android", null)).use { scenario ->
                    await(scenario, "$gate must replace the generic GIF/video fallback hint") {
                        holder(it)?.item?.isVideo == true &&
                            it.findViewById<android.widget.TextView>(R.id.selection_message).text.contains(expected)
                    }
                    scenario.onMainActivity { assertFalse(it.findViewById<View>(R.id.paste_action).isEnabled) }
                    if (gate == "gallery_unavailable") {
                        val hold = InstrumentationRegistry.getArguments().getString("nativeGateHoldMs")?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
                        if (hold > 0) kotlinx.coroutines.delay(hold)
                    }
                }
                registry.clear()
            }
        } finally { registry.clear(); FloatingSession.active(beforeActive) }
    }
    @Test fun focusedVideoLoopsMutedAndReleasesWhenHiddenStoppedOrBrowsedAway(): Unit = runBlocking {
        val prefs = FloatingPreferences(app); val preferred = prefs.current().preferStickers; prefs.preferStickers(true)
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "nu.gpu.nagramx", null)).use { scenario ->
                await(scenario, "Focused MP4 must render frames") { holder(it)?.binding?.videoPreview?.let { view -> view.alpha == 1f && view.player?.isPlaying == true } == true }
                var initial: Player? = null
                scenario.onActivity {
                    initial = holder(it)!!.binding.videoPreview.player
                    assertEquals(0f, initial!!.volume, 0f); assertEquals(Player.REPEAT_MODE_ONE, initial!!.repeatMode)
                    assertTrue(holder(it)!!.itemView.performClick())
                }
                await(scenario, "Selecting MP4 must copy original video") { it.getSystemService(ClipboardManager::class.java).primaryClipDescription?.hasMimeType("video/mp4") == true }
                scenario.onActivity {
                    val clip = it.getSystemService(ClipboardManager::class.java).primaryClip!!
                    assertTrue(bytes.contentEquals(it.contentResolver.openInputStream(clip.getItemAt(0).uri)!!.use { stream -> stream.readBytes() }))
                    assertFalse(it.findViewById<View>(R.id.sticker_action).isEnabled)
                    assertTrue(it.findViewById<View>(R.id.media_action).isEnabled)
                    it.findViewById<View>(R.id.source_toggle).performClick()
                }
                await(scenario, "Source pane must release its hidden player") { holder(it)?.binding?.videoPreview?.player == null }
                scenario.onActivity { it.findViewById<View>(R.id.source_back).performClick() }
                await(scenario, "Returning to carousel must start one fresh player") { holder(it)?.binding?.videoPreview?.player?.let { p -> p !== initial && p.isPlaying } == true }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { assertNull(holder(it)?.binding?.videoPreview?.player) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario, "Resuming picker must restore focused playback") { holder(it)?.binding?.videoPreview?.player?.isPlaying == true }
                scenario.onActivity { it.findViewById<RecyclerView>(R.id.media_grid).smoothScrollToPosition(4) }
                await(scenario, "Browsing to still image must release video") {
                    val model = ViewModelProvider(it)[PickerViewModel::class.java]
                    model.focusedId != null && model.focusedId != it.findViewById<RecyclerView>(R.id.media_grid)
                        .let { grid -> (grid.adapter as MediaCarouselAdapter).currentList.first().id } &&
                        holder(it)?.binding?.videoPreview?.player == null
                }
            }
        } finally { prefs.preferStickers(preferred) }
    }
}
