package one.njk.sao.floating.nativebridge

import one.njk.sao.source.*

import one.njk.sao.floating.PickerMarqueeRule

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
    @get:org.junit.Rule val marquees = PickerMarqueeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private var previous: SourceCatalog? = null
    private var previousActive = false
    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.sources().select(null); access.browseSession().reset()
        NativeBridgeRegistry.clear(); previousActive = FloatingSession.active.value; FloatingSession.active(true)
    }
    @After fun cleanup() = runBlocking {
        NativeBridgeRegistry.clear(); FloatingSession.active(previousActive); previous?.let { access.sources().restore(it) }; access.browseSession().reset()
    }
    private open class Policy : ComposerPolicy {
        var identity: String? = "synthetic-thread-a"
        var draft = false
        override fun renewFocusedDraft() = draft
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
    @Test fun delayedMetadataRecoversNaturalConnectionButChangedEstablishedChatRevokesIt() {
        withHost { activity, editor ->
            val recorder = Recorder(editor); val policy = Policy().apply { identity = null }
            val session = NativeEditorSession(policy, app.packageName)
            session.capture(activity, editor, recorder, EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") })
            val pending = session.snapshot(activity, true)
            assertEquals("pending_metadata", pending.getString("status")); assertNull(pending.getString("session"))
            policy.identity = "synthetic-thread-a"
            assertNotNull("Delayed metadata should recover the original natural connection", session.snapshot(activity, true).getString("session"))
            policy.identity = null
            assertEquals("editor_changed", session.snapshot(activity, true).getString("status"))
            policy.identity = "synthetic-thread-b"
            assertNull("A changed established chat needs a new connection", session.snapshot(activity, true).getString("session"))
            assertEquals(0, recorder.calls)
        }
    }
    @Test fun inactiveFloatingServiceStopsNativeSnapshotCallsAndClearsLeases() = runBlocking {
        val snapshots = AtomicInteger(); val control = java.util.concurrent.atomic.AtomicReference<Boolean>()
        FloatingSession.active(false)
        NativeBridgeRegistry.register("com.discord", object : INativeStickerClient.Stub() {
            override fun setActive(active: Boolean, generation: Long) { control.set(active) }
            override fun snapshot(beginSelection: Boolean) = Bundle().apply {
                snapshots.incrementAndGet(); putString("session", "idle-check")
                putLong("created", SystemClock.elapsedRealtime()); putBoolean("resumed", true)
                putStringArray("mime_types", arrayOf("image/gif"))
            }
            override fun insert(uri: Uri, mimeType: String, session: String, requestId: Long) = false
        })
        withTimeout(5_000) { while (control.get() != false) delay(20) }
        delay(900); assertEquals("Inactive registration must not poll Android/native clients", 0, snapshots.get())
        FloatingSession.active(true)
        withTimeout(5_000) { while (!NativeBridgeRegistry.supports("com.discord", "idle-check", "image/gif")) delay(20) }
        assertTrue(snapshots.get() > 0)
        FloatingSession.active(false)
        withTimeout(5_000) { while (control.get() != false) delay(20) }
        val settled = snapshots.get(); delay(900)
        assertEquals("No snapshots after Stop has settled", settled, snapshots.get())
        assertNull(NativeBridgeRegistry.cachedSelection("com.discord")); assertTrue(NativeBridgeRegistry.sessions.value.isEmpty())
        assertFalse(NativeBridgeRegistry.available("com.discord"))
    }
    @Test fun stoppedFloatingDisablesAccessibilityEventsAndRestartRestoresThem() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = FloatingPreferences(app); val previousOptions = preferences.current()
        val beforeActive = FloatingSession.active.value
        val resolver = app.contentResolver
        val servicesKey = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val enabledKey = android.provider.Settings.Secure.ACCESSIBILITY_ENABLED
        val previousServices = android.provider.Settings.Secure.getString(resolver, servicesKey)
        val previousEnabled = android.provider.Settings.Secure.getInt(resolver, enabledKey, 0)
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            preferences.pasteAssistance(true); FloatingSession.active(false)
            val component = android.content.ComponentName(app, PasteAssistService::class.java).flattenToString()
            val services = previousServices.orEmpty().split(':').filter { it.isNotBlank() }.toMutableSet().apply { add(component) }
            android.provider.Settings.Secure.putString(resolver, servicesKey, services.joinToString(":"))
            android.provider.Settings.Secure.putInt(resolver, enabledKey, 1)
            val instanceField = PasteAssistService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            fun events(): Int? {
                val service = (instanceField.get(null) as java.lang.ref.WeakReference<*>).get() as? PasteAssistService
                return service?.serviceInfo?.eventTypes
            }
            withTimeout(10_000) { while (!PasteAssistService.connected() || events() != 0) delay(40) }
            FloatingSession.active(true)
            withTimeout(10_000) { while (events() != (android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)) delay(40) }
            FloatingSession.active(false)
            withTimeout(10_000) { while (events() != 0) delay(40) }
            assertNull(PasteAssistService.prepareTarget())
        } finally {
            FloatingSession.active(false)
            android.provider.Settings.Secure.putString(resolver, servicesKey, previousServices)
            android.provider.Settings.Secure.putInt(resolver, enabledKey, previousEnabled)
            preferences.pasteAssistance(previousOptions.pasteAssistance)
            automation.dropShellPermissionIdentity()
            FloatingSession.active(beforeActive)
        }
    }
    @Test fun whatsappUsesLiveComposerMetadataWithoutMissingOrStaleLaunchIntent() {
        withHost { activity, _ ->
            val editor = NativeBridgeFixtures.WhatsappEditor(activity).apply {
                conversation.putString("ARG_JID", "synthetic-chat-a")
            }
            activity.setContentView(editor); editor.requestFocus()
            val policy = WhatsappComposerPolicy(); val recorder = Recorder(editor)
            val session = NativeEditorSession(policy, app.packageName)
            assertNull(activity.intent.getStringExtra("jid"))
            assertNotNull("Live editor metadata does not require a launch-extra jid", policy.identity(activity, editor))
            session.capture(activity, editor, recorder, EditorInfo().apply { contentMimeTypes = arrayOf("image/webp.wasticker") })
            val original = session.snapshot(activity, true).getString("session")!!
            activity.intent.putExtra("jid", "stale-launch-chat")
            assertEquals(original, session.snapshot(activity, true).getString("session"))
            editor.conversation.putString("ARG_JID", "synthetic-chat-b")
            assertNull(session.snapshot(activity, true).getString("session"))
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/v1/fixture.webp")
            assertFalse(session.insert(activity, true, uri, "image/webp.wasticker", original, 1))
            assertEquals(0, recorder.calls)
            editor.other.putString("ARG_JID", "ambiguous-other-chat")
            assertNull("Conflicting live composer metadata stays disabled", policy.identity(activity, editor))
            editor.other.clear(); editor.conversation.clear()
            assertNull("An activity jid alone cannot identify the live editor", policy.identity(activity, editor))
        }
    }
    @Test fun receiveContentLeaseSurvivesBlurAndNeedsNoInputConnection() {
        withHost { activity, editor ->
            val calls = AtomicInteger()
            editor.setOnReceiveContentListener(arrayOf("image/gif")) { _, _ -> calls.incrementAndGet(); null }
            val policy = object : Policy() {
                override fun viewMimeTypes(view: EditText) = view.receiveContentMimeTypes.orEmpty()
                override fun insertContent(view: EditText, input: android.view.inputmethod.InputConnection?, uri: Uri, mime: String): Boolean {
                    if (!view.hasFocus() && !view.requestFocus()) return false
                    val clip = android.content.ClipData(android.content.ClipDescription("fixture", arrayOf(mime)), android.content.ClipData.Item(uri))
                    return view.performReceiveContent(android.view.ContentInfo.Builder(clip, android.view.ContentInfo.SOURCE_APP).build()) == null
                }
            }
            (editor.parent as android.view.ViewGroup).removeView(editor)
            val other = android.view.View(activity).apply { isFocusableInTouchMode = true }
            val parent = android.widget.FrameLayout(activity).apply { addView(editor); addView(other, android.widget.FrameLayout.LayoutParams(1, 1)) }
            activity.setContentView(parent); assertTrue(other.requestFocus()); assertFalse(editor.hasFocus())
            val session = NativeEditorSession(policy, app.packageName)
            session.captureView(activity, editor)
            val original = session.snapshot(activity, true).getString("session")!!
            assertEquals(original, session.snapshot(activity, false).getString("session"))
            assertEquals(original, session.snapshot(activity, true).getString("session"))
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/v1/fixture.gif")
            assertFalse(session.insert(activity, false, uri, "image/gif", original, 1))
            assertEquals(0, calls.get())
            assertTrue(session.insert(activity, true, uri, "image/gif", original, 1))
            assertFalse(session.insert(activity, true, uri, "image/gif", original, 2))
            assertEquals(1, calls.get())
        }
    }
    @Test fun receiveContentCapabilityRemovalAndHiddenEditorRevokeLease() {
        withHost { activity, editor ->
            editor.setOnReceiveContentListener(arrayOf("image/gif")) { _, _ -> null }
            val policy = object : Policy() {
                override fun viewMimeTypes(view: EditText) = view.receiveContentMimeTypes.orEmpty()
            }
            val session = NativeEditorSession(policy, app.packageName)
            session.captureView(activity, editor)
            assertNotNull(session.snapshot(activity, true).getString("session"))
            editor.setOnReceiveContentListener(null, null)
            assertNull(session.snapshot(activity, true).getString("session"))
            editor.setOnReceiveContentListener(arrayOf("image/gif")) { _, _ -> null }
            session.captureView(activity, editor)
            assertNotNull(session.snapshot(activity, true).getString("session"))
            editor.visibility = android.view.View.GONE
            assertNull(session.snapshot(activity, true).getString("session"))
        }
    }
    @Test fun instagramDiscoveryFollowsDelegateRatherThanMediaStoreGraphs() {
        withHost { _, editor ->
            val owner = NativeBridgeFixtures.Owner(editor, "synthetic-noisy-thread")
            val binding = InstagramThreadBinding.discover(NativeBridgeFixtures.noisyListener(owner), editor, NativeBridgeFixtures.Key::class.java)
            assertNotNull("Realistic media-store branches must not exhaust controller discovery", binding)
            assertNotNull(binding!!.identity(editor))
        }
    }
    @Test fun draftTypingRenewsForegroundSelectionButCannotRetargetPausedSelection() {
        withHost { activity, editor ->
            val recorder = Recorder(editor); val policy = Policy().apply { draft = true }
            val session = NativeEditorSession(policy, app.packageName)
            session.capture(activity, editor, recorder, EditorInfo().apply { contentMimeTypes = arrayOf("image/gif") })
            val original = session.snapshot(activity, true).getString("session")!!
            policy.identity = "revision-after-typing"
            val fresh = session.snapshot(activity, true).getString("session")!!
            assertNotEquals(original, fresh)
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/v1/fixture.gif")
            assertFalse(session.insert(activity, true, uri, "image/gif", original, 1))
            policy.identity = "revision-while-picker-open"
            assertNull(session.snapshot(activity, false).getString("session"))
            assertFalse(session.insert(activity, true, uri, "image/gif", fresh, 2))
            assertEquals(0, recorder.calls)
        }
    }
    @Test fun visibilityBootstrapMakesServiceVisibleWithoutGrantingMedia() = runBlocking {
        val probe = "one.njk.sao.discordprobe"
        org.junit.Assume.assumeTrue("Install the isolated Discord probe with its launcher entry",
            runCatching { app.packageManager.getApplicationInfo(probe, 0) }.isSuccess)
        NativeBridgeVisibility.prepare(app)
        val file = java.io.File(app.filesDir, "native_bridge/ready")
        assertEquals(0, file.length())
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        // The isolated probe intentionally has no SAO <queries> declaration. Give it exactly
        // the same empty-file grant as a scoped host, then run its no-query binding check.
        try { app.grantUriPermission(probe, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (error: IllegalArgumentException) { org.junit.Assume.assumeNoException("Install the isolated Discord probe", error) }
        // Android force-stops the instrumented app on completion, revoking transient URI
        // grants. The external probe can optionally run while this test keeps SAO alive.
        val hold = InstrumentationRegistry.getArguments().getString("visibility_probe_hold_ms")?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
        if (hold > 0) delay(hold)
    }
    @Test fun whatsappRequiresExactStickerDeclarationAndPreservesNativeImageMimeTypes() {
        val policy = WhatsappComposerPolicy()
        assertEquals(listOf("image/jpeg", "image/png", "image/gif"), policy.mimeTypes(EditorInfo().apply {
            contentMimeTypes = arrayOf("image/*", "video/*")
        }).toList())
        assertEquals(listOf("image/webp.wasticker", "image/jpeg", "image/png"), policy.mimeTypes(EditorInfo().apply {
            contentMimeTypes = arrayOf("image/webp.wasticker", "image/jpeg", "image/png")
        }).toList())
    }
    @Test fun whatsappPastePrioritizesNativeStickerThenMediaWithoutAccessibility() = runBlocking {
        val preferences = FloatingPreferences(app); val before = preferences.current()
        try {
            preferences.pasteAssistance(false)
            for (sticker in listOf(true, false)) {
                preferences.preferStickers(sticker)
                assertNativePaste("com.whatsapp", LocalMediaSource(app).all().first { !it.hasMotion },
                    if (sticker) "image/webp.wasticker" else "image/jpeg", arrayOf("image/webp.wasticker", "image/jpeg"))
            }
        } finally { preferences.preferStickers(before.preferStickers); preferences.pasteAssistance(before.pasteAssistance) }
    }
    @Test fun preparedVideoRechecksChatCapabilitiesAndOneUseDispatch() {
        withHost { activity, editor ->
            var videoAvailable = true
            val policy = object : Policy() {
                override fun additionalMimeTypes(view: EditText) = if (videoAvailable) arrayOf("video/mp4") else emptyArray()
            }
            val session = NativeEditorSession(policy, app.packageName)
            val info = EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") }
            val input = Recorder(editor)
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/clip.mp4")
            session.capture(activity, editor, input, info)
            val original = session.snapshot(activity, true).getString("session")!!
            assertTrue(session.accepts(activity, true, uri, "video/mp4", original, 1))
            policy.identity = "another-chat" // Changed while video preparation was running on IO.
            var calls = 0
            val prepared = PreparedNativeContent { _, _ -> calls++; false }
            assertFalse(session.insert(activity, true, uri, "video/mp4", original, 1, prepared))
            assertEquals(0, calls)
            session.capture(activity, editor, input, info)
            val next = session.snapshot(activity, true).getString("session")!!
            videoAvailable = false
            assertFalse(session.insert(activity, true, uri, "video/mp4", next, 2, prepared))
            assertEquals(0, calls)
            videoAvailable = true
            assertFalse(session.insert(activity, true, uri, "video/mp4", next, 3, prepared))
            assertEquals(1, calls)
            assertFalse(session.insert(activity, true, uri, "video/mp4", next, 4, prepared))
            assertEquals("An unknown native result must never replay a video", 1, calls)
            assertEquals("Gallery video must bypass keyboard-image commitContent", 0, input.calls)
        }
    }
    @Test fun lateVideoCapabilityUpdatesTheOriginalLeaseWithoutAnotherKeyboardConnection() {
        withHost { activity, editor ->
            var available = false
            val policy = object : Policy() {
                override fun additionalMimeTypes(view: EditText) = if (available) arrayOf("video/mp4") else emptyArray()
            }
            val input = Recorder(editor)
            val session = NativeEditorSession(policy, app.packageName)
            session.capture(activity, editor, input, EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") })
            val old = session.snapshot(activity, true).getString("session")!!
            available = true // Gallery contract finished loading on the host IO worker.
            val state = session.snapshot(activity, true)
            val next = state.getString("session")!!
            assertEquals("A late MIME capability must not strand the picker on an obsolete token", old, next)
            assertArrayEquals(arrayOf("image/jpeg", "video/mp4"), state.getStringArray("mime_types"))
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/clip.mp4")
            val prepared = PreparedNativeContent { _, connection -> assertSame(input, connection); true }
            assertTrue(session.insert(activity, true, uri, "video/mp4", old, 1, prepared))
            assertFalse(session.insert(activity, true, uri, "video/mp4", next, 2, prepared))
            assertEquals(0, input.calls)
        }
    }
    @Test fun explicitSelectionRecoversRevokedDraftWithoutReconnectingOrReplaying() {
        withHost { activity, editor ->
            val policy = Policy().apply { draft = true }
            val input = Recorder(editor)
            val session = NativeEditorSession(policy, app.packageName)
            session.capture(activity, editor, input, EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") })
            val original = session.beginSelection(activity, true).getString("session")!!
            policy.identity = "draft-changed-while-picker-open"
            assertNull(session.snapshot(activity, false).getString("session"))
            assertNull("Foreground polling must not re-arm a revoked selection", session.snapshot(activity, true).getString("session"))
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/fixture.jpg")
            assertFalse(session.insert(activity, true, uri, "image/jpeg", original, 1))
            val next = session.beginSelection(activity, true).getString("session")!!
            assertNotEquals(original, next)
            assertFalse(session.insert(activity, true, uri, "image/jpeg", original, 2))
            input.accept = false
            assertFalse(session.insert(activity, true, uri, "image/jpeg", next, 3))
            assertFalse(session.insert(activity, true, uri, "image/jpeg", next, 4))
            val recovered = session.beginSelection(activity, true).getString("session")!!
            input.accept = true
            assertTrue(session.insert(activity, true, uri, "image/jpeg", recovered, 5))
            assertEquals("One failed action and one successful action, without a keyboard reconnect", 2, input.calls)
        }
    }
    @Test fun nativeVideoAndNaturalKeyboardPhotoCapabilitiesCoexist() {
        withHost { activity, editor ->
            val policy = object : Policy() {
                override fun viewMimeTypes(view: EditText) = arrayOf("video/mp4")
            }
            val input = Recorder(editor)
            val session = NativeEditorSession(policy, app.packageName)
            session.capture(activity, editor, input, EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") })
            val state = session.beginSelection(activity, true)
            assertEquals(setOf("video/mp4", "image/jpeg"), state.getStringArray("mime_types")!!.toSet())
            val uri = Uri.parse("content://${app.packageName}.fileprovider/floating_exports/fixture.jpg")
            assertTrue(session.insert(activity, true, uri, "image/jpeg", state.getString("session"), 1))
            assertEquals(1, input.calls)
        }
    }
    @Test fun lateNativeViewRouteSurvivesBlurAndRemovalKeepsOnlyKeyboardTypes() {
        withHost { activity, editor ->
            var available = false
            val policy = object : Policy() {
                override fun viewMimeTypes(view: EditText) = if (available) arrayOf("video/mp4") else emptyArray()
            }
            val session = NativeEditorSession(policy, app.packageName)
            session.capture(activity, editor, Recorder(editor), EditorInfo().apply { contentMimeTypes = arrayOf("image/jpeg") })
            val token = session.snapshot(activity, true).getString("session")!!
            (editor.parent as android.view.ViewGroup).removeView(editor)
            val other = android.view.View(activity).apply { isFocusableInTouchMode = true }
            activity.setContentView(android.widget.FrameLayout(activity).apply {
                addView(editor); addView(other, android.widget.FrameLayout.LayoutParams(1, 1))
            })
            assertTrue(other.requestFocus()); assertFalse(editor.hasFocus())
            available = true
            val state = session.snapshot(activity, true)
            assertEquals(token, state.getString("session")); assertTrue(state.getBoolean("resumed"))
            assertEquals(setOf("image/jpeg", "video/mp4"), state.getStringArray("mime_types")!!.toSet())
            available = false
            val removed = session.snapshot(activity, true)
            assertEquals(token, removed.getString("session")); assertFalse(removed.getBoolean("resumed"))
            assertArrayEquals(arrayOf("image/jpeg"), removed.getStringArray("mime_types"))
            assertFalse(session.accepts(activity, true,
                Uri.parse("content://${app.packageName}.fileprovider/floating_exports/clip.mp4"), "video/mp4", token, 1))
        }
    }
    @Test fun explicitSelectionRecoversClientFailureWithoutWaitingForPollingBackoff() = runBlocking {
        val beginCalls = AtomicInteger(); val normalCalls = AtomicInteger()
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        NativeBridgeRegistry.register("com.discord", object : INativeStickerClient.Stub() {
            override fun setActive(active: Boolean, generation: Long) = Unit
            override fun snapshot(beginSelection: Boolean): Bundle {
                if (beginSelection) beginCalls.incrementAndGet() else normalCalls.incrementAndGet()
                if (failing.get()) throw IllegalStateException("Synthetic host recreation")
                return Bundle().apply {
                    putString("session", "recovered"); putLong("created", SystemClock.elapsedRealtime())
                    putBoolean("resumed", true); putStringArray("mime_types", arrayOf("video/mp4"))
                }
            }
            override fun insert(uri: Uri, mimeType: String, session: String, requestId: Long) = false
        })
        withTimeout(5_000) { while (normalCalls.get() == 0) delay(20) }
        assertEquals("Polling never begins a new selection", 0, beginCalls.get())
        assertNull(NativeBridgeRegistry.beginSelection("com.discord"))
        failing.set(false)
        val selected = NativeBridgeRegistry.beginSelection("com.discord")!!
        assertEquals("recovered", selected.session)
        assertTrue(NativeBridgeRegistry.supports("com.discord", selected.session, "video/mp4"))
        assertEquals(2, beginCalls.get())
        withTimeout(5_000) { while (normalCalls.get() < 2) delay(20) }
        assertEquals("Normal refreshes cannot rotate the explicit selection", 2, beginCalls.get())
    }
    @Test fun rejectedAttemptDoesNotRevokeTheFileAccessOfAnEarlierAttachment() = runBlocking {
        val pkg = "com.discord"
        val uid = app.packageManager.getPackageUid(pkg, 0)
        val media = MediaExports(app, LocalMediaSource(app)).prepare(LocalMediaSource(app).all().first { !it.hasMotion }, Rendition.MEDIA)
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val calls = AtomicInteger()
        NativeBridgeRegistry.register(pkg, object : INativeStickerClient.Stub() {
            override fun setActive(active: Boolean, generation: Long) = Unit
            override fun snapshot(beginSelection: Boolean) = Bundle().apply {
                putString("session", "grant-recovery"); putLong("created", SystemClock.elapsedRealtime())
                putBoolean("resumed", true); putStringArray("mime_types", arrayOf(media.mimeType))
            }
            override fun insert(uri: Uri, mimeType: String, session: String, requestId: Long): Boolean { calls.incrementAndGet(); return false }
        })
        app.grantUriPermission(pkg, media.uri, read)
        try {
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, app.checkUriPermission(media.uri, -1, uid, read))
            assertFalse(NativeBridgeRegistry.insert(app, pkg, media.uri, media.mimeType, "grant-recovery"))
            assertEquals(1, calls.get())
            assertEquals("Failure must preserve access held by a previously staged attachment",
                android.content.pm.PackageManager.PERMISSION_GRANTED, app.checkUriPermission(media.uri, -1, uid, read))
        } finally { app.revokeUriPermission(pkg, media.uri, read) }
    }
    @Test fun discordNativeGifStagesDraftOnceWithoutAccessibility() = runBlocking {
        val preferences = FloatingPreferences(app); val before = preferences.current()
        try {
            preferences.pasteAssistance(false)
            assertNativePaste("com.discord", LocalMediaSource(app).all().first { it.animated }, "image/gif", arrayOf("image/jpeg", "image/png", "image/gif"))
        } finally { preferences.pasteAssistance(before.pasteAssistance) }
    }
    private suspend fun assertNativePaste(pkg: String, item: MediaItem, expectedMime: String, types: Array<String>) {
        val calls = AtomicInteger(); var delivered: String? = null
        NativeBridgeRegistry.register(pkg, object : INativeStickerClient.Stub() {
            override fun setActive(active: Boolean, generation: Long) = Unit
            override fun snapshot(beginSelection: Boolean) = Bundle().apply {
                putString("session", "priority-session"); putLong("created", SystemClock.elapsedRealtime())
                putStringArray("mime_types", types); putBoolean("resumed", true)
                putString("status", "ready"); putString("host_version", "fixture-version")
            }
            override fun insert(uri: Uri, mimeType: String, session: String, requestId: Long): Boolean {
                delivered = mimeType; calls.incrementAndGet(); return true
            }
        })
        withTimeout(5_000) { while (!NativeBridgeRegistry.supports(pkg, "priority-session", expectedMime)) delay(20) }
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, pkg, null, "priority-session")).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 10_000; var selected = false
            while (!selected && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity {
                    val grid = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.media_grid)
                    val adapter = grid.adapter as MediaCarouselAdapter
                    val position = adapter.currentList.indexOfFirst { media -> media.id == item.id }
                    if (position >= 0) {
                        val focal = (grid.onFlingListener as com.google.android.material.carousel.CarouselSnapHelper).findSnapView(grid.layoutManager!!)
                        selected = focal?.let { (grid.getChildViewHolder(it) as MediaCarouselAdapter.Holder).item?.id == item.id } == true &&
                            it.findViewById<android.view.View>(R.id.paste_action).isEnabled
                        if (!selected) grid.scrollToPosition(position)
                    }
                }; delay(40)
            }
            assertTrue(selected)
            assertEquals("Browsing must not insert", 0, calls.get())
            scenario.onActivity { assertNull("Native Paste must require no image tap", androidx.lifecycle.ViewModelProvider(it)[PickerViewModel::class.java].selection.value) }
            scenario.onActivity {
                val paste = it.findViewById<android.view.View>(R.id.paste_action)
                assertTrue("Verified native Paste must work without an accessibility anchor", paste.isEnabled)
                assertTrue(paste.performClick())
            }
            withTimeout(5_000) { while (calls.get() == 0) delay(20) }
            assertEquals(expectedMime, delivered); assertEquals(1, calls.get())
        }
        NativeBridgeRegistry.clear()
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
            override fun setActive(active: Boolean, generation: Long) = Unit
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
            override fun setActive(active: Boolean, generation: Long) = Unit
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
                        if (position >= 0) {
                            val focal = (grid.onFlingListener as com.google.android.material.carousel.CarouselSnapHelper).findSnapView(grid.layoutManager!!)
                            selected = focal?.let { (grid.getChildViewHolder(it) as MediaCarouselAdapter.Holder).item?.id == item.id } == true &&
                                it.findViewById<android.widget.TextView>(R.id.selection_title).text.toString() == item.title
                            if (!selected) grid.scrollToPosition(position)
                        }
                    }; delay(40)
                }
                assertTrue(selected)
                scenario.onActivity {
                    val send = it.findViewById<android.widget.TextView>(R.id.paste_action)
                    assertEquals("Paste", send.text.toString()); assertEquals(!item.hasMotion, send.isEnabled)
                    assertFalse(it.findViewById<android.view.View>(R.id.sticker_action).isEnabled)
                }
            }
            assertEquals("Browsing must not call the native send handler", 0, calls.get())
            scenario.onActivity { assertNull(androidx.lifecycle.ViewModelProvider(it)[PickerViewModel::class.java].selection.value) }
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
