package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.*
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Installed Instagram receiver checks and local fixture transport; no account or DM send. */
class InstagramDeviceTest {
    @get:org.junit.Rule val marquees = PickerMarqueeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private var previous: SourceCatalog? = null

    @Before fun setup() = runBlocking {
        Assume.assumeTrue("Install Instagram before this integration test",
            runCatching { app.packageManager.getApplicationInfo(InstagramDelivery.PACKAGE, 0) }.isSuccess)
        assertNotNull("Launch Instagram to initialize its photo receiver", InstagramDelivery.receiver(app.packageManager, "image/jpeg"))
        assertNotNull("Launch Instagram to initialize its video receiver", InstagramDelivery.receiver(app.packageManager, "video/mp4"))
        previous = access.sources().current(); access.sources().select(null); access.browseSession().reset()
    }
    @After fun cleanup() = runBlocking {
        previous?.let { access.sources().restore(it) }
        access.browseSession().reset()
    }

    @Test fun dmReceiverAndCrossUidGrantKeepJpegGifMp4AndWebmPayloads() = runBlocking {
        val local = LocalMediaSource(app)
        val exports = MediaExports(app, local)
        val still = exports.prepare(local.all().first { !it.hasMotion }, Rendition.MEDIA)
        val gif = exports.prepare(local.all().first { it.animated }, Rendition.MEDIA)
        val videos = listOf("mp4", "webm").map { extension ->
            val file = File(app.filesDir, "floating_exports/v1/instagram-fixture.$extension")
            file.writeBytes(instrumentation.context.assets.open("preview-video.$extension").use { it.readBytes() })
            ExportedMedia(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file), "video/$extension", file)
        }
        try {
            for (media in listOf(still, gif) + videos) {
                val component = requireNotNull(InstagramDelivery.receiver(app.packageManager, media.mimeType))
                assertEquals(InstagramDelivery.PACKAGE, component.packageName)
                assertTrue(component.className.startsWith("com.instagram.direct.share.handler."))
                val send = InstagramDelivery.intent(media, component, "Local fixture")
                assertEquals(InstagramDelivery.ACTION, send.action)
                assertEquals(component, send.component)
                assertEquals(media.mimeType, send.type)
                assertEquals(media.mimeType, send.clipData!!.description.getMimeType(0))
                assertEquals(media.uri, send.clipData!!.getItemAt(0).uri)
                assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                receiveUnderOtherUid(send, media)
            }
            assertNull(InstagramDelivery.receiver(app.packageManager, "application/octet-stream"))
        } finally { videos.forEach { it.file.delete() } }
    }

    private fun receiveUnderOtherUid(send: Intent, media: ExportedMedia) {
        val nonce = UUID.randomUUID().toString(); val ready = CountDownLatch(1); val result = AtomicReference<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("nonce") == nonce) { result.set(intent); ready.countDown() }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(MediaReceiverProbeActivity.RESULT), ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, InstagramDelivery.PACKAGE, null)).use { scenario ->
                scenario.onActivity { it.startActivity(Intent(send).setPackage(instrumentation.context.packageName)
                    .setComponent(ComponentName(instrumentation.context.packageName, MediaReceiverProbeActivity::class.java.name))
                    .putExtra("nonce", nonce).putExtra("return_package", app.packageName)) }
                assertTrue("Fixture receiver must read the grant", ready.await(15, TimeUnit.SECONDS))
                val received = result.get()
                assertNotEquals(Process.myUid(), received.getIntExtra("receiver_uid", -1))
                assertNull(received.getStringExtra("error"))
                assertEquals(InstagramDelivery.ACTION, received.getStringExtra("outer_action"))
                assertEquals(media.mimeType, received.getStringExtra("outer_mime"))
                assertEquals(media.mimeType, received.getStringExtra("mime"))
                assertEquals(media.file.length().toInt(), received.getIntExtra("length", -1))
                val hash = MessageDigest.getInstance("SHA-256").digest(media.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hash, received.getStringExtra("sha256"))
            }
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun selectingGifCopiesImmediatelyAndOffersOnlyDmAttachment() {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, InstagramDelivery.PACKAGE, null)).use { scenario ->
            selectGif(scenario)
            await(scenario) { it.findViewById<View>(R.id.media_action).isEnabled }
            scenario.onActivity {
                assertEquals("DM", it.findViewById<TextView>(R.id.media_action).text.toString())
                assertTrue(it.findViewById<TextView>(R.id.selection_message).text.toString().endsWith(it.getString(R.string.picker_instagram_hint)))
                assertFalse(it.findViewById<View>(R.id.paste_action).isEnabled)
                assertFalse(it.findViewById<View>(R.id.sticker_action).isEnabled)
            }
            val image = instrumentation.uiAutomation.takeScreenshot()
            File(app.filesDir, "instagram-dm-picker.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            // Exercise the actual button while intercepting the external launch: no DM is sent.
            val filter = IntentFilter(InstagramDelivery.ACTION).apply { addDataType("*/*") }
            val monitor = instrumentation.addMonitor(filter, null, true)
            try {
                scenario.onActivity { it.findViewById<View>(R.id.media_action).performClick() }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (monitor.hits == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
                assertEquals("DM must use Instagram's DM action", 1, monitor.hits)
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun disabledDmReceiverRetainsCopyAndDoesNotOfferFeedOrStoriesFallback() {
        val state = app.packageManager.getApplicationEnabledSetting(InstagramDelivery.PACKAGE)
        fun change(command: String) {
            instrumentation.uiAutomation.executeShellCommand("pm $command --user 0 ${InstagramDelivery.PACKAGE}")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
        try {
            change("disable-user")
            assertNull(InstagramDelivery.receiver(app.packageManager, "image/gif"))
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, InstagramDelivery.PACKAGE, null)).use { scenario ->
                selectGif(scenario)
                scenario.onActivity {
                    assertTrue(it.findViewById<View>(R.id.copy_action).isEnabled)
                    assertFalse(it.findViewById<View>(R.id.media_action).isEnabled)
                    assertFalse(it.findViewById<View>(R.id.paste_action).isEnabled)
                    assertTrue(it.findViewById<TextView>(R.id.selection_message).text.toString().endsWith(it.getString(R.string.picker_instagram_unavailable)))
                }
            }
        } finally { change(if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) "default-state" else "enable") }
    }

    private fun selectGif(scenario: ActivityScenario<PickerActivity>) {
        val gif = runBlocking { LocalMediaSource(app).all().first { it.animated } }
        await(scenario) { activity ->
            val grid = activity.findViewById<RecyclerView>(R.id.media_grid)
            val adapter = grid.adapter as MediaCarouselAdapter
            val position = adapter.currentList.indexOfFirst { it.id == gif.id }
            if (position < 0) false else {
                grid.scrollToPosition(position)
                grid.findViewHolderForAdapterPosition(position)?.itemView?.performClick() == true
            }
        }
        await(scenario) { activity ->
            val manager = activity.getSystemService(ClipboardManager::class.java)
            manager.primaryClipDescription?.hasMimeType("image/gif") == true &&
                manager.primaryClip?.getItemAt(0)?.uri?.toString()?.contains(gif.id) == true &&
                activity.findViewById<TextView>(R.id.selection_message).text.toString().startsWith(activity.getString(R.string.picker_gif_copied))
        }
        scenario.onActivity {
            val uri = it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).uri
            assertArrayEquals(app.assets.open(gif.asset).use { s -> s.readBytes() }, it.contentResolver.openInputStream(uri)!!.use { s -> s.readBytes() })
        }
    }
    private fun await(scenario: ActivityScenario<PickerActivity>, condition: (PickerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000; var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) { scenario.onActivity { ready = condition(it) }; SystemClock.sleep(40) }
        assertTrue("Picker condition must be met", ready)
    }
}
