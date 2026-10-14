package one.njk.sao.floating

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Node IPC is serialized off Main. No conversation text is read and Send is never pressed. */
@AndroidEntryPoint
class PasteAssistService : AccessibilityService() {
    @Inject lateinit var preferences: FloatingPreferences
    @Inject lateinit var dispatchers: FloatingDispatchers
    private lateinit var scope: CoroutineScope
    private val events = Channel<Unit>(Channel.CONFLATED)
    private val operations = Mutex()
    private val discoveryVersion = AtomicLong()
    @Volatile private var cached: Anchor? = null
    @Volatile private var lease: Anchor? = null
    @Volatile private var supported = AppFamilyResolver.supported(emptySet())
    private data class Anchor(val node: AccessibilityNodeInfo, val packageName: String,
        val token: String = UUID.randomUUID().toString(), val capturedAt: Long = SystemClock.elapsedRealtime())

    override fun onServiceConnected() {
        scope = CoroutineScope(SupervisorJob() + dispatchers.assistance)
        instance = WeakReference(this)
        // Reconnection may return the previously disabled dynamic ServiceInfo. Use the
        // discovery contract from paste_assistance.xml, rather than persisting that zero.
        val enabledEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED
        scope.launch {
            var appliedPackages: Set<String>? = null
            var appliedEvents = -1
            combine(preferences.options, FloatingSession.active) { options, active -> options to active }.collect { (options, active) ->
                supported = AppFamilyResolver.supported(options.telegramPackages)
                val eventTypes = if (active && options.pasteAssistance) enabledEvents else 0
                if (appliedPackages != supported || appliedEvents != eventTypes) {
                    withContext(dispatchers.main) {
                        serviceInfo = serviceInfo.apply { packageNames = supported.toTypedArray(); this.eventTypes = eventTypes }
                    }
                    appliedPackages = supported; appliedEvents = eventTypes
                }
                if (!active || !options.pasteAssistance) { cached = null; lease = null }
                if (eventTypes != 0) events.trySend(Unit)
            }
        }
        scope.launch {
            for (event in events) operations.withLock {
                if (!FloatingSession.active.value || FloatingSession.pickerVisible || !preferences.current().pasteAssistance) return@withLock
                val version = discoveryVersion.get()
                try {
                    val root = rootInActiveWindow
                    val pkg = root?.packageName?.toString()
                    val editor = if (pkg in supported) root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null
                    if (version != discoveryVersion.get() || !FloatingSession.active.value || FloatingSession.pickerVisible) return@withLock
                    // A client may not advertise Paste until the clipboard contains media.
                    // Lease the focused editable node now; dispatch is still explicit and
                    // revalidates that exact node after the selected payload is copied.
                    cached = if (pkg != null && editor != null && editor.isEditable && editor.isFocused)
                        Anchor(editor, pkg) else null
                } catch (_: IllegalStateException) { cached = null }
                catch (_: SecurityException) { cached = null }
            }
        }
        // Refresh only while the user-started session is active, never while SAO owns focus.
        scope.launch {
            FloatingSession.active.collectLatest { active ->
                if (!active) { discoveryVersion.incrementAndGet(); cached = null; lease = null }
                if (active) while (isActive) {
                    if (!FloatingSession.pickerVisible) events.trySend(Unit)
                    delay(750)
                }
            }
        }
        events.trySend(Unit)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!FloatingSession.active.value) return
        discoveryVersion.incrementAndGet(); cached = null; events.trySend(Unit)
    }
    override fun onInterrupt() { discoveryVersion.incrementAndGet(); cached = null; lease = null }
    override fun onDestroy() {
        instance.clear(); if (::scope.isInitialized) scope.cancel(); events.close(); cached = null; lease = null
        super.onDestroy()
    }

    private fun capture(pkg: String?): String? {
        val anchor = cached?.takeIf { it.packageName == pkg && SystemClock.elapsedRealtime() - it.capturedAt <= 1500 } ?: return null
        lease = anchor
        return anchor.token
    }

    private fun pasteOnce(token: String, pkg: String): Boolean {
        if (!FloatingSession.active.value || pkg !in supported) return false
        val anchor = lease?.takeIf { it.token == token && it.packageName == pkg } ?: return false
        lease = null // Consume before dispatch: at most one action, even with repeated taps.
        scope.launch {
            operations.withLock {
                repeat(15) {
                    delay(100)
                    if (!FloatingSession.active.value || SystemClock.elapsedRealtime() - anchor.capturedAt > 120_000) return@withLock
                    try {
                        val root = rootInActiveWindow
                        if (root?.packageName?.toString() != pkg) return@repeat
                        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (anchor.node.refresh() && anchor.node == current && anchor.node.isFocused && anchor.node.isEditable)
                            anchor.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        return@withLock
                    } catch (_: IllegalStateException) { return@withLock }
                    catch (_: SecurityException) { return@withLock }
                }
            }
        }
        return true
    }
    companion object {
        private var instance = WeakReference<PasteAssistService>(null)
        fun connected() = instance.get() != null
        fun capturePackage(): String? = instance.get()?.cached?.takeIf {
            SystemClock.elapsedRealtime() - it.capturedAt <= 1500
        }?.packageName
        fun captureAnchor(packageName: String?): String? = instance.get()?.capture(packageName)
        fun requestPaste(token: String?, packageName: String?): Boolean =
            if (token == null || packageName == null) false else instance.get()?.pasteOnce(token, packageName) ?: false
    }
}
