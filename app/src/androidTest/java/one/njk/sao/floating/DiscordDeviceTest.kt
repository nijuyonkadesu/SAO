package one.njk.sao.floating

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import one.njk.sao.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@androidx.annotation.OptIn(UnstableApi::class)
class DiscordDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private lateinit var server: MockWebServer
    private var previous = SourceCatalog()
    private lateinit var webm: ByteArray
    private lateinit var mp4: ByteArray
    private val webmId = "7da8d1d6-53d6-43e9-aed1-dbe12f5e14dc"
    private val mp4Id = "7da8d1d6-53d6-43e9-aed1-dbe12f5e14dd"

    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.browseSession().reset()
        webm = instrumentation.context.assets.open("preview-video.webm").use { it.readBytes() }
        mp4 = instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }
        val sample = LocalMediaSource(app).all().first { !it.animated }
        val poster = app.assets.open(sample.asset).use { it.readBytes() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/tags" -> MockResponse().setBody("[]")
                    request.path?.contains("/thumbnail") == true -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(poster))
                    request.path?.contains("/$webmId/original") == true -> MockResponse().setHeader("Content-Type", "video/webm").setBody(Buffer().write(webm))
                    request.path?.contains("/$mp4Id/original") == true -> MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(mp4))
                    else -> MockResponse().setBody("""{"assets":{"items":[
                        {"id":"$webmId","type":"VIDEO","originalFileName":"reaction.WEBM","originalMimeType":"application/octet-stream","updatedAt":"2026-10-10T00:00:00Z"},
                        {"id":"$mp4Id","type":"VIDEO","originalFileName":"reaction.mp4","originalMimeType":"video/mp4; charset=binary","updatedAt":"2026-10-10T00:00:00Z"}
                    ]}}""")
                }
            }
            start()
        }
        access.immichSettings().save(ImmichConfig(server.url("/").toString(), "discord-contract-key"))
    }

    @After fun cleanup() = runBlocking {
        access.sources().restore(previous); access.browseSession().reset(); server.shutdown()
    }

    @Test fun gifWebmAndMp4ArriveAsOriginalAttachmentsUnderAnotherUid() = runBlocking {
        val local = LocalMediaSource(app)
        val gif = local.all().first { it.animated }
        val remote = SwitchingMediaSource(app, local, access.immichSettings(), FloatingDispatchers())
        val videos = remote.page(MediaQuery()).items
        assertEquals(listOf("video/webm", "video/mp4"), videos.map { it.mimeType })
        val cases = listOf(
            Triple(MediaExports(app, local).prepare(gif, Rendition.MEDIA), app.assets.open(gif.asset).use { it.readBytes() }, "gif"),
            Triple(MediaExports(app, remote).prepare(videos[0], Rendition.MEDIA), webm, "webm"),
            Triple(MediaExports(app, remote).prepare(videos[1], Rendition.MEDIA), mp4, "mp4"),
        )
        for ((exported, bytes, extension) in cases) {
            assertTrue(exported.file.name.endsWith(".$extension"))
            assertArrayEquals(bytes, exported.file.readBytes())
            verifyHandoff(exported, bytes)
        }
    }

    private fun verifyHandoff(exported: ExportedMedia, bytes: ByteArray) {
        val nonce = UUID.randomUUID().toString()
        val ready = CountDownLatch(1)
        val result = AtomicReference<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("nonce") == nonce) { result.set(intent); ready.countDown() }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(MediaReceiverProbeActivity.RESULT), ContextCompat.RECEIVER_EXPORTED)
        try {
            val send = MediaDelivery.intent(exported, "com.discord", "Reaction")
            assertEquals("com.discord", send.`package`)
            assertEquals(exported.mimeType, send.clipData!!.description.getMimeType(0))
            assertEquals(exported.uri, send.clipData!!.getItemAt(0).uri)
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.discord", null)).use { scenario ->
                // A genuine receiver UID reads SAO's private FileProvider data using the production handoff.
                scenario.onActivity { activity ->
                    activity.startActivity(send.setPackage(instrumentation.context.packageName)
                        .setComponent(ComponentName(instrumentation.context.packageName, MediaReceiverProbeActivity::class.java.name))
                        .putExtra("nonce", nonce).putExtra("return_package", app.packageName))
                }
                assertTrue("Receiver did not return", ready.await(15, TimeUnit.SECONDS))
                assertNotEquals(Process.myUid(), result.get().getIntExtra("receiver_uid", -1))
                assertNull(result.get().getStringExtra("error"))
                assertEquals(exported.mimeType, result.get().getStringExtra("outer_mime"))
                assertEquals(exported.mimeType, result.get().getStringExtra("mime"))
                assertEquals(bytes.size, result.get().getIntExtra("length", -1))
                val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, result.get().getStringExtra("sha256"))
            }
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun discordWebmPlaysMutedAndCopiesWithoutOfferingVideoPasteOrSticker() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.discord", null)).use { scenario ->
            fun await(label: String, condition: (PickerActivity) -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 15_000; var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = condition(it) }; SystemClock.sleep(40)
                }
                assertTrue(label, ready)
            }
            fun holder(activity: PickerActivity) = activity.findViewById<RecyclerView>(R.id.media_grid)
                .findViewHolderForAdapterPosition(0) as? MediaCarouselAdapter.Holder
            await("Focused WebM must render video frames") { holder(it)?.binding?.videoPreview?.let { v -> v.alpha == 1f && v.player?.isPlaying == true } == true }
            scenario.onActivity {
                val player = holder(it)!!.binding.videoPreview.player!!
                assertEquals(0f, player.volume, 0f); assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
                assertTrue(holder(it)!!.itemView.performClick())
            }
            await("Selecting WebM copies original binary video") {
                val clipboard = it.getSystemService(ClipboardManager::class.java)
                val id = holder(it)?.item?.id
                id != null && clipboard.primaryClipDescription?.hasMimeType("video/webm") == true &&
                    clipboard.primaryClip?.getItemAt(0)?.uri?.toString()?.contains(id) == true
            }
            scenario.onActivity {
                val clip = it.getSystemService(ClipboardManager::class.java).primaryClip!!
                assertArrayEquals(webm, it.contentResolver.openInputStream(clip.getItemAt(0).uri)!!.use { stream -> stream.readBytes() })
                assertEquals("Attach", it.findViewById<TextView>(R.id.media_action).text.toString())
                assertTrue(it.findViewById<View>(R.id.media_action).isEnabled)
                assertFalse(it.findViewById<View>(R.id.paste_action).isEnabled)
                assertFalse(it.findViewById<View>(R.id.sticker_action).isEnabled)
                assertEquals(it.getString(R.string.picker_discord_motion_hint), it.findViewById<TextView>(R.id.capability_hint).text.toString())
            }
        }
    }

    @Test fun discordGifSelectionKeepsAnimationAndUsesImagePasteGuidance() {
        val gif = runBlocking {
            access.sources().select(null)
            LocalMediaSource(app).all().first { it.mimeType == "image/gif" }
        }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.discord", null)).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var selected = false
            while (!selected && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val carousel = activity.findViewById<RecyclerView>(R.id.media_grid)
                    val adapter = carousel.adapter as MediaCarouselAdapter
                    val position = adapter.currentList.indexOfFirst { it.id == gif.id }
                    if (position >= 0) {
                        carousel.scrollToPosition(position)
                        selected = carousel.findViewHolderForAdapterPosition(position)?.itemView?.performClick() == true
                    }
                }
                SystemClock.sleep(40)
            }
            assertTrue("Bundled GIF must be selectable", selected)
            var copied = false
            while (!copied && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity {
                    copied = it.getSystemService(ClipboardManager::class.java).primaryClipDescription?.hasMimeType("image/gif") == true &&
                        it.findViewById<TextView>(R.id.selection_status).text.toString() == it.getString(R.string.picker_gif_copied)
                }
                SystemClock.sleep(40)
            }
            assertTrue("GIF must copy as an image URI", copied)
            scenario.onActivity {
                val uri = it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).uri
                assertArrayEquals(app.assets.open(gif.asset).use { stream -> stream.readBytes() },
                    it.contentResolver.openInputStream(uri)!!.use { stream -> stream.readBytes() })
                assertEquals(it.getString(R.string.picker_discord_paste_inactive), it.findViewById<TextView>(R.id.capability_hint).text.toString())
                assertFalse(it.findViewById<View>(R.id.sticker_action).isEnabled)
                assertTrue(it.findViewById<View>(R.id.media_action).isEnabled)
                // Launching without an editor lease must not silently paste into another chat.
                assertFalse(it.findViewById<View>(R.id.paste_action).isEnabled)
            }
        }
    }
}
