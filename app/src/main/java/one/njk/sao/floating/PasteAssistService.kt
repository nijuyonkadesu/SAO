package one.njk.sao.floating

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.njk.sao.R
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/** Event-driven discovery and explicit, bounded handoff. Node IPC never runs on Main. */
@AndroidEntryPoint
class PasteAssistService : AccessibilityService() {
    @Inject lateinit var preferences: FloatingPreferences
    @Inject lateinit var dispatchers: FloatingDispatchers
    @Inject lateinit var runtime: FloatingRuntime
    private lateinit var scope: CoroutineScope
    private val events = Channel<Unit>(Channel.CONFLATED)
    private val operations = Mutex()
    @Volatile private var cached: Editor? = null
    private val lease = AtomicReference<Lease?>()
    @Volatile private var enabled = false
    @Volatile private var supported = AppFamilyResolver.supported(emptySet())
    @Volatile private var pending: Job? = null
    private data class Editor(val node: AccessibilityNodeInfo, val packageName: String, val windowId: Int)
    private data class Lease(val editor: Editor, val token: String = UUID.randomUUID().toString())
    data class Capture(val packageName: String, val token: String?)
    data class Result(val token: String, val packageName: String, val outcome: PasteOutcome)

    override fun onServiceConnected() {
        // Android can reconnect this genuine system-bound service after an OEM clears the
        // started-service record. Restore once, using durable user intent, not editor events.
        runtime.restore()
        if (::scope.isInitialized) scope.cancel()
        cached = null; lease.set(null); pending?.cancel()
        scope = CoroutineScope(SupervisorJob() + dispatchers.assistance)
        instance = WeakReference(this)
        val enabledEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED
        scope.launch {
            var appliedPackages: Set<String>? = null
            var appliedEvents = -1
            combine(preferences.options, FloatingSession.active) { options, active -> options to active }.collect { (options, active) ->
                supported = AppFamilyResolver.supported(options.telegramPackages)
                enabled = active && options.pasteAssistance
                val eventTypes = if (enabled) enabledEvents else 0
                if (appliedPackages != supported || appliedEvents != eventTypes) {
                    withContext(dispatchers.main) {
                        serviceInfo = serviceInfo.apply { packageNames = supported.toTypedArray(); this.eventTypes = eventTypes }
                    }
                    appliedPackages = supported; appliedEvents = eventTypes
                }
                if (!enabled) { cached = null; lease.set(null); pending?.cancel() }
                else events.trySend(Unit)
            }
        }
        scope.launch {
            for (event in events) operations.withLock {
                if (!canDiscover()) return@withLock
                try { discover() }
                catch (_: IllegalStateException) { cached = null }
                catch (_: SecurityException) { cached = null }
                if (!canDiscover()) cached = null
            }
        }
    }

    /** Remember the last focused editor, including across a subsequent loss of input focus. */
    private fun discover(): Capture? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        if (pkg !in supported || pkg == null) { cached = null; return null }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val previous = cached?.takeIf { it.packageName == pkg && it.windowId == root.windowId }
        cached = when {
            focused?.isEditable == true -> if (usable(focused) && focused.isFocused) Editor(focused, pkg, root.windowId) else null
            previous != null && previous.node.refresh() && usable(previous.node) -> previous
            else -> null
        }
        return Capture(pkg, null)
    }

    private fun usable(node: AccessibilityNodeInfo) = node.isEditable && node.isEnabled && node.isVisibleToUser && !node.isPassword
    private fun canDiscover() = enabled && FloatingSession.active.value && !FloatingSession.pickerVisible

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (canDiscover() && event?.packageName?.toString() in supported) events.trySend(Unit)
    }
    override fun onInterrupt() { cached = null; lease.set(null); pending?.cancel() }
    override fun onDestroy() {
        enabled = false; instance.clear(); if (::scope.isInitialized) scope.cancel()
        events.close(); cached = null; lease.set(null)
        super.onDestroy()
    }

    private fun capture(pkg: String?): String? {
        if (!enabled || !FloatingSession.active.value) return null
        val editor = cached?.takeIf { it.packageName == pkg } ?: return null
        return Lease(editor).also { lease.set(it) }.token
    }

    private suspend fun prepare(): Capture? {
        if (!canDiscover()) return null
        pending?.cancel(); lease.set(null)
        // Service ownership lets the UI proceed after its deadline even if Binder stalls.
        val scan = scope.async {
            operations.withLock {
                if (!canDiscover()) return@withLock null
                val result = discover() ?: return@withLock null
                currentCoroutineContext().ensureActive()
                if (!canDiscover()) null else result.copy(token = capture(result.packageName))
            }
        }
        return try { withTimeoutOrNull(200) { scan.await() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { null }
        finally { scan.cancel() }
    }

    private fun pasteOnce(token: String, pkg: String): Boolean {
        if (!enabled || !FloatingSession.active.value || pkg !in supported) return false
        val selected = lease.get()?.takeIf { it.token == token && it.editor.packageName == pkg } ?: return false
        if (!lease.compareAndSet(selected, null)) return false
        pending?.cancel(); resultState.value = null
        pending = scope.launch {
            val outcome = try {
                operations.withLock {
                    val original = selected.editor
                    attemptPaste(object : PasteEndpoint {
                        override fun inspect(): PasteReadiness {
                            val root = rootInActiveWindow ?: return PasteReadiness.WAITING
                            if (root.packageName?.toString() != pkg) return PasteReadiness.WAITING
                            if (root.windowId != original.windowId || !original.node.refresh()) return PasteReadiness.CHANGED
                            if (!usable(original.node)) return PasteReadiness.WAITING
                            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            if (focused != null && focused.isEditable && focused != original.node) return PasteReadiness.CHANGED
                            return if (focused == original.node && original.node.isFocused) PasteReadiness.READY else PasteReadiness.NEEDS_FOCUS
                        }
                        override fun focus() = original.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        override fun paste() = original.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }, enabled = { enabled && FloatingSession.active.value })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalStateException) { PasteOutcome.UNAVAILABLE }
            catch (_: SecurityException) { PasteOutcome.UNAVAILABLE }
            currentCoroutineContext().ensureActive()
            resultState.value = Result(token, pkg, outcome)
            // Android accepting the action does not prove attachment readiness or Send.
            if (outcome == PasteOutcome.UNAVAILABLE || outcome == PasteOutcome.UNCONFIRMED) withContext(dispatchers.main) {
                Toast.makeText(applicationContext, if (outcome == PasteOutcome.UNCONFIRMED)
                    R.string.floating_paste_unconfirmed else R.string.floating_paste_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        return true // Queued; the actual result is published separately.
    }

    companion object {
        private var instance = WeakReference<PasteAssistService>(null)
        private val resultState = MutableStateFlow<Result?>(null)
        val results = resultState.asStateFlow()
        fun connected() = instance.get() != null
        suspend fun prepareTarget(): Capture? = instance.get()?.prepare()
        fun requestPaste(token: String?, packageName: String?): Boolean =
            if (token == null || packageName == null) false else instance.get()?.pasteOnce(token, packageName) ?: false
    }
}
