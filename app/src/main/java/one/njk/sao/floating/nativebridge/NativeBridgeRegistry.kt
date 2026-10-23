package one.njk.sao.floating.nativebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import one.njk.sao.floating.FloatingSession

/** Native state is pushed only while observed; an idle floating icon performs no snapshot IPC. */
object NativeBridgeRegistry {
    const val PROTOCOL_REVISION = NativeBridgeProtocol.REVISION
    data class Selection(val packageName: String, val session: String, val mimeTypes: Set<String>)
    data class HostStatus(val stage: String, val version: String, val videoStage: String = "",
        val revision: Int = 0, val build: String = "")
    private data class Snapshot(val selection: Selection?, val status: HostStatus, val resumed: Boolean)
    private class Client(val remote: INativeStickerClient, val uid: Int) {
        val binder: IBinder = remote.asBinder()
        var death: IBinder.DeathRecipient? = null
        lateinit var observer: INativeStateObserver
        val state = MutableStateFlow<Snapshot?>(null)
        @Volatile var inserting = false
        @Volatile var observing = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val operations = Mutex()
    private val clients = ConcurrentHashMap<String, Client>()
    private val cached = ConcurrentHashMap<String, Pair<Selection, Long>>()
    private val connection = MutableStateFlow(false)
    val connected = connection.asStateFlow()
    private val statusState = MutableStateFlow<Map<String, HostStatus>>(emptyMap())
    val statuses = statusState.asStateFlow()
    private val sessionState = MutableStateFlow<Map<String, Selection>>(emptyMap())
    val sessions = sessionState.asStateFlow()
    fun status(packageName: String?) = statusState.value[packageName]

    init {
        scope.launch {
            FloatingSession.active.collect { active ->
                if (!active) { cached.clear(); sessionState.value = emptyMap() }
                val control = FloatingSession.controlState()
                clients.values.forEach { client ->
                    runCatching { client.remote.setActive(control.active, control.generation) }
                    observe(client)
                }
                connection.value = active && clients.isNotEmpty()
            }
        }
        scope.launch { FloatingSession.pickerVisibility.collect { clients.values.forEach(::observe) } }
    }
    private fun wanted(client: Client) = FloatingSession.active.value && (FloatingSession.pickerVisible || client.inserting)
    @Synchronized private fun observe(client: Client) {
        val observing = wanted(client)
        if (client.observing == observing) return
        client.observing = observing
        try { client.remote.observe(if (observing) client.observer else null) }
        catch (_: android.os.RemoteException) { client.observing = false }
    }
    private fun unlink(client: Client) {
        runCatching { client.remote.observe(null) }
        client.death?.let { runCatching { client.binder.unlinkToDeath(it, 0) } }
    }
    @Synchronized internal fun register(packageName: String, remote: INativeStickerClient,
        uid: Int = android.os.Process.myUid()) {
        val binder = remote.asBinder()
        if (clients[packageName]?.binder == binder) return
        val client = Client(remote, uid)
        client.observer = object : INativeStateObserver.Stub() {
            override fun changed(state: Bundle?) {
                if (Binder.getCallingUid() != client.uid) throw SecurityException("Unexpected native state publisher")
                if (state == null) return
                val snapshot = decode(packageName, state)
                scope.launch { if (wanted(client)) accept(packageName, client, snapshot) }
            }
        }
        client.death = IBinder.DeathRecipient {
            if (clients.remove(packageName, client)) {
                cached.remove(packageName)
                sessionState.update { it - packageName }; statusState.update { it - packageName }
                connection.value = FloatingSession.active.value && clients.isNotEmpty()
            }
        }
        binder.linkToDeath(client.death!!, 0)
        clients.put(packageName, client)?.let(::unlink)
        cached.remove(packageName); sessionState.update { it - packageName }
        statusState.update { it + (packageName to HostStatus("waiting_editor", "")) }
        connection.value = FloatingSession.active.value
        scope.launch {
            if (clients[packageName] === client) {
                val control = FloatingSession.controlState()
                runCatching { remote.setActive(control.active, control.generation) }
                observe(client)
            }
        }
    }
    private fun decode(packageName: String, state: Bundle) = Snapshot(
        selection(packageName, state), hostStatus(state), state.getBoolean("resumed"))
    private fun accept(packageName: String, client: Client, state: Snapshot) {
        if (!FloatingSession.active.value || clients[packageName] !== client) return
        client.state.value = state
        statusState.update { it + (packageName to state.status) }
        sessionState.update { current -> state.selection?.let { current + (packageName to it) } ?: (current - packageName) }
        if (state.resumed && state.selection != null) cached[packageName] = state.selection to SystemClock.elapsedRealtime()
        else cached.remove(packageName)
    }
    private fun hostStatus(state: Bundle) = HostStatus(
        state.getString("status").orEmpty().takeIf {
            it in setOf("waiting_editor", "unsupported_mime", "pending_metadata", "editor_changed", "unfocused", "ready", "hooks_unavailable", "selection_timeout")
        } ?: "waiting_editor",
        state.getString("host_version").orEmpty().take(40),
        state.getString("video_status").orEmpty().takeIf {
            it in setOf("gallery_loading", "gallery_unavailable", "ambiguous_composer", "waiting_editor", "thread_unresolved", "pending_metadata", "media_restricted", "sender_missing", "ready")
        }.orEmpty(), state.getInt("bridge_revision"),
        state.getString("host_build").orEmpty().take(20).filter(Char::isDigit))
    private fun selection(packageName: String, state: Bundle): Selection? {
        val session = state.getString("session")?.takeIf { it.length in 1..128 } ?: return null
        val age = SystemClock.elapsedRealtime() - state.getLong("created")
        if (age !in 0..NativeEditorSession.MAX_AGE_MS) return null
        val mimeTypes = state.getStringArray("mime_types")?.takeIf { it.size <= 32 }?.toSet().orEmpty()
        if (mimeTypes.isEmpty() || mimeTypes.any { it.length > 128 }) return null
        return Selection(packageName, session, mimeTypes)
    }
    @Synchronized internal fun clear() {
        clients.values.forEach(::unlink)
        clients.clear(); cached.clear()
        sessionState.value = emptyMap(); statusState.value = emptyMap(); connection.value = false
    }
    fun available(packageName: String?) = FloatingSession.active.value && packageName != null && clients[packageName]?.binder?.isBinderAlive == true
    fun supports(packageName: String?, session: String?, mimeType: String): Boolean =
        available(packageName) && session != null && sessionState.value[packageName]?.let {
            it.session == session && mimeType in it.mimeTypes
        } == true
    fun cachedSelection(packageName: String?): Selection? {
        val now = SystemClock.elapsedRealtime()
        return cached.values.filter { (selection, time) ->
            (packageName == null || selection.packageName == packageName) && now - time < 1000
        }.singleOrNull()?.first
    }
    suspend fun beginSelection(packageName: String?): Selection? = withContext(Dispatchers.IO) {
        operations.withLock {
            if (!FloatingSession.active.value) return@withLock null
            // Unknown foreground: an explicit icon press may query the small registered set once.
            val names = packageName?.let(::listOf) ?: clients.keys.toList()
            val selections = names.mapNotNull { name ->
                val client = clients[name] ?: return@mapNotNull null
                try {
                    val state = decode(name, client.remote.snapshot(true))
                    accept(name, client, state)
                    state.selection.takeIf { state.resumed && clients[name] === client && FloatingSession.active.value }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: android.os.RemoteException) { null }
                catch (_: RuntimeException) { null }
            }
            selections.singleOrNull()
        }
    }
    suspend fun insert(context: Context, packageName: String, uri: Uri, mimeType: String,
        session: String): NativeInsertResult = withContext(Dispatchers.IO) {
        operations.withLock {
            if (!FloatingSession.active.value) return@withLock NativeInsertResult.REJECTED
            val client = clients[packageName] ?: return@withLock NativeInsertResult.REJECTED
            var granted = false
            var dispatched = false
            var outcome = NativeInsertResult.REJECTED
            client.inserting = true
            try {
                observe(client)
                accept(packageName, client, decode(packageName, client.remote.snapshot(false)))
                // Focus recovery is event-driven and bounded to this explicit user operation.
                val ready = withTimeoutOrNull(2000) {
                    combine(client.state, FloatingSession.active) { state, active -> state to active }
                        .first { (state, active) -> !active || clients[packageName] !== client ||
                            state != null && (state.selection?.session != session || mimeType !in state.selection.mimeTypes || state.resumed) }
                }
                val state = ready?.first
                if (ready?.second != true || clients[packageName] !== client || state?.resumed != true ||
                    state.selection?.session != session || mimeType !in state.selection.mimeTypes) return@withLock outcome
                val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val uid = context.packageManager.getPackageUid(packageName, 0)
                if (context.checkUriPermission(uri, -1, uid, read) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    context.grantUriPermission(packageName, uri, read); granted = true
                }
                dispatched = true
                outcome = NativeInsertResult.fromWire(client.remote.insert(uri, mimeType, session, SystemClock.elapsedRealtimeNanos()))
                outcome
            } catch (cancelled: CancellationException) { if (dispatched) outcome = NativeInsertResult.UNKNOWN; throw cancelled }
            catch (_: Exception) {
                outcome = if (dispatched) NativeInsertResult.UNKNOWN else NativeInsertResult.REJECTED
                outcome
            } finally {
                // An uncertain send must retain its grant: the host may already be reading it.
                if (granted && outcome == NativeInsertResult.REJECTED) runCatching { context.revokeUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                client.inserting = false; observe(client)
                cached.remove(packageName); sessionState.update { it - packageName }
            }
        }
    }
}
