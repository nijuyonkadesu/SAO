package one.njk.sao.floating.nativebridge

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import one.njk.sao.R
import one.njk.sao.floating.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import X.NativeBridgeFixtures

/** Exercises the production leases/adapters/registry with recording receivers; never sends. */
class NativeBridgeDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private var previous: SourceCatalog? = null
    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.sources().select(null); access.browseSession().reset()
        NativeBridgeRegistry.clear()
    }
    @After fun cleanup() = runBlocking {
        NativeBridgeRegistry.clear(); previous?.let { access.sources().restore(it) }; access.browseSession().reset()
    }
    private class Policy : ComposerPolicy {
        var identity: String? = "synthetic-thread-a"
        override fun isComposer(view: EditText) = view.id == R.id.search
        override fun identity(activity: Activity, view: EditText) = identity
        override fun mimeTypes(info: EditorInfo) = info.contentMimeTypes.orEmpty()
    }
    private class Recorder(view: EditText) : BaseInputConnection(view, false) {
        var calls = 0; var mime: String? = null; var accept = true
        override fun commitContent(content: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
            calls++; mime = content.description.getMimeType(0); assertEquals(0, flags); return accept
        }
    }
    @Test fun oneUseLeaseRejectsWrongUriMimePausedHostAndReplay() {
        withHost { activity, editor ->
            val recorder = Recorder(editor); val policy = Policy()
            val session = NativeEditorSession(policy, app.packageName)
            val info = EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") }
            session.capture(activity, editor, recorder, info)
            val token = session.snapshot(activity, true).getString("session")!!
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/v1/fixture.jpg")
            assertFalse(session.insert(activity, true, Uri.parse("file:///tmp/fixture.jpg"), "image/jpeg", token, 1))
            assertFalse(session.insert(activity, true, Uri.parse("content://wrong.provider/fixture.jpg"), "image/jpeg", token, 1))
            assertFalse(session.insert(activity, true, uri, "image/gif", token, 1))
            assertFalse(session.insert(activity, false, uri, "image/jpeg", token, 1))
            assertEquals(0, recorder.calls)
            assertTrue(session.insert(activity, true, uri, "image/jpeg", token, 1))
            assertFalse(session.insert(activity, true, uri, "image/jpeg", token, 2))
            assertEquals(1, recorder.calls); assertEquals("image/jpeg", recorder.mime)
            val next = session.snapshot(activity, true).getString("session")!!
            recorder.accept = false
            assertFalse(session.insert(activity, true, uri, "image/jpeg", next, 3))
            assertFalse(session.insert(activity, true, uri, "image/jpeg", next, 4))
            assertEquals("A rejected native call also consumes its lease", 2, recorder.calls)
        }
    }
    @Test fun chatSwapRecreationExpiryAndDetachedEditorInvalidateOriginalLease() {
        withHost { activity, editor ->
            val recorder = Recorder(editor); val policy = Policy(); val clock = AtomicLong(SystemClock.elapsedRealtime())
            val session = NativeEditorSession(policy, app.packageName, clock::get)
            val info = EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") }
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/v1/fixture.jpg")
            session.capture(activity, editor, recorder, info)
            val original = session.snapshot(activity, true).getString("session")!!
            assertEquals(original, session.snapshot(activity, false).getString("session"))
            session.capture(activity, editor, recorder, info) // Natural IME reconnect after closing picker.
            assertEquals(original, session.snapshot(activity, true).getString("session"))
            policy.identity = "synthetic-thread-b"
            assertFalse(session.insert(activity, true, uri, "image/jpeg", original, 1))
            policy.identity = "synthetic-thread-a"
            assertNull(session.snapshot(activity, true).getString("session"))
            session.capture(activity, editor, recorder, info)
            val renewed = session.snapshot(activity, true).getString("session")!!
            assertNotEquals(original, renewed)
            clock.addAndGet(NativeEditorSession.MAX_AGE_MS + 1)
            assertFalse(session.insert(activity, true, uri, "image/jpeg", renewed, 2))
            val fresh = session.snapshot(activity, true).getString("session")!!
            activity.setContentView(EditText(activity))
            assertFalse(session.insert(activity, true, uri, "image/jpeg", fresh, 3))
            assertEquals(0, recorder.calls)
        }
    }
    @Test fun instagramBindingTracksMutableThreadKeyAndRejectsAmbiguousOrReplacedEditors() {
        withHost { _, editor ->
            val owner = NativeBridgeFixtures.Owner(editor, "thread-a")
            val listener = NativeBridgeFixtures.listener(owner)
            val binding = InstagramThreadBinding.discover(listener, editor, NativeBridgeFixtures.Key::class.java)!!
            val original = binding.identity(editor)!!
            owner.key.id = ""
            assertNull(binding.identity(editor))
            owner.key.id = "thread-b"
            assertNotEquals(original, binding.identity(editor))
            owner.container.editor = EditText(editor.context)
            assertNull(binding.identity(editor))
            owner.container.editor = editor
            val other = NativeBridgeFixtures.Owner(editor, "other")
            assertNull(InstagramThreadBinding.discover(NativeBridgeFixtures.Pair(owner, other), editor, NativeBridgeFixtures.Key::class.java))
            assertNull(InstagramThreadBinding.discover(NativeBridgeFixtures.Link("not a controller"), editor, NativeBridgeFixtures.Key::class.java))
        }
    }
    @Test fun registryUsesActualMimeAndNeverRetriesRejectedSend() = runBlocking {
        val inserts = AtomicInteger(); val snapshots = AtomicInteger(); var deliveredMime: String? = null
        val client = object : INativeStickerClient.Stub() {
            override fun snapshot(beginSelection: Boolean) = Bundle().apply {
                putString("session", "fixture-session"); putLong("created", SystemClock.elapsedRealtime())
                putStringArray("mime_types", arrayOf("image/jpeg")); putBoolean("resumed", snapshots.incrementAndGet() >= 3)
            }
            override fun insert(uri: Uri, mimeType: String, session: String, requestId: Long): Boolean {
                inserts.incrementAndGet(); deliveredMime = mimeType; return false
            }
        }
        NativeBridgeRegistry.register("com.instagram.android", client)
        val media = MediaExports(app, LocalMediaSource(app)).prepare(LocalMediaSource(app).all().first { !it.hasMotion }, Rendition.MEDIA)
        assertFalse(NativeBridgeRegistry.insert(app, "com.instagram.android", media.uri, "image/gif", "fixture-session"))
        assertEquals(0, inserts.get())
        assertFalse(NativeBridgeRegistry.insert(app, "com.instagram.android", media.uri, media.mimeType, "fixture-session"))
        assertEquals(1, inserts.get()); assertEquals("image/jpeg", deliveredMime)
        NativeBridgeRegistry.clear(); assertFalse(NativeBridgeRegistry.available("com.instagram.android"))
    }
    @Test fun verifiedInstagramLeaseEnablesOnlyExplicitPhotoActionWithoutAccessibility() = runBlocking {
        val calls = AtomicInteger()
        NativeBridgeRegistry.register("com.instagram.android", object : INativeStickerClient.Stub() {
            override fun snapshot(beginSelection: Boolean) = Bundle().apply {
                putString("session", "ui-session"); putLong("created", SystemClock.elapsedRealtime())
                putStringArray("mime_types", arrayOf("image/jpeg", "image/png")); putBoolean("resumed", true)
            }
            override fun insert(uri: Uri, mimeType: String, session: String, requestId: Long): Boolean { calls.incrementAndGet(); return true }
        })
        withTimeout(5_000) { while (!NativeBridgeRegistry.supports("com.instagram.android", "ui-session", "image/jpeg")) delay(20) }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "com.instagram.android", null, "ui-session")).use { scenario ->
            val local = LocalMediaSource(app).all(); val photo = local.first { !it.hasMotion }; val gif = local.first { it.animated }
            for (item in listOf(photo, gif, photo)) {
                val deadline = SystemClock.elapsedRealtime() + 10_000; var selected = false
                while (!selected && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        val grid = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                        val adapter = grid.adapter as MediaCarouselAdapter
                        val position = adapter.currentList.indexOfFirst { media -> media.id == item.id }
                        if (position >= 0) { grid.scrollToPosition(position); selected = grid.findViewHolderForAdapterPosition(position)?.itemView?.performClick() == true }
                    }; delay(40)
                }
                assertTrue(selected)
                scenario.onActivity {
                    val send = it.findViewById<android.widget.TextView>(R.id.paste_action)
                    assertEquals("Paste", send.text.toString()); assertEquals(!item.hasMotion, send.isEnabled)
                    assertFalse(it.findViewById<android.view.View>(R.id.sticker_action).isEnabled)
                }
            }
            assertEquals("Highlight/copy must not call the native send handler", 0, calls.get())
            scenario.onActivity { assertTrue(it.findViewById<android.view.View>(R.id.paste_action).performClick()) }
            withTimeout(5_000) { while (calls.get() == 0) delay(20) }
            assertEquals("Explicit Instagram Paste dispatches once", 1, calls.get())
        }
    }
    private fun withHost(block: (Activity, EditText) -> Unit) {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            lateinit var editor: EditText
            scenario.onActivity { editor = EditText(it).apply { id = R.id.search }; it.setContentView(editor); editor.requestFocus() }
            val deadline = SystemClock.elapsedRealtime() + 5_000; var focused = false
            while (!focused && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { focused = it.hasWindowFocus() && editor.hasFocus() }; SystemClock.sleep(30)
            }
            assertTrue("Synthetic editor must have the actual window focus", focused)
            scenario.onActivity { block(it, editor) }
        }
    }
}
