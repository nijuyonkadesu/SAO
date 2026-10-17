package one.njk.sao.floating.nativebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import one.njk.sao.floating.FloatingSession

object NativeBridgeRegistry {
    data class Selection(val packageName: String, val session: String, val mimeTypes: Set<String>)
    private data class Client(val remote: INativeStickerClient, val binder: IBinder, var death: IBinder.DeathRecipient? = null,
        var failures: Int = 0, var retryAfter: Long = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val operations = Mutex()
    private val cached = ConcurrentHashMap<String, Pair<Selection, Long>>()
    private var polling: Job? = null
    private val clients = ConcurrentHashMap<String, Client>()
    private val connection = MutableStateFlow(false)
    val connected = connection.asStateFlow()
    data class HostStatus(val stage: String, val version: String)
    private val statusState = MutableStateFlow<Map<String, HostStatus>>(emptyMap())
    val statuses = statusState.asStateFlow()
    fun status(packageName: String?) = statusState.value[packageName]
    private val sessionState = MutableStateFlow<Map<String, Selection>>(emptyMap())
    val sessions = sessionState.asStateFlow()

    init {
        scope.launch {
            FloatingSession.active.collect { active ->
                if (!active) {
                    polling?.cancel(); polling = null; cached.clear(); sessionState.value = emptyMap()
                }
                operations.withLock {
                    val control = FloatingSession.controlState()
                    clients.forEach { (_, client) -> runCatching { client.remote.setActive(control.active, control.generation) } }
                }
                connection.value = active && clients.isNotEmpty()
                if (active) startPolling()
            }
        }
    }
    private fun unlink(client: Client) {
        client.death?.let { runCatching { client.binder.unlinkToDeath(it, 0) } }
    }
    @Synchronized internal fun register(packageName: String, remote: INativeStickerClient) {
        val binder = remote.asBinder()
        if (clients[packageName]?.binder == binder) return
        val client = Client(remote, binder)
        val death = IBinder.DeathRecipient {
            if (clients.remove(packageName, client)) {
                cached.remove(packageName)
                sessionState.value = sessionState.value - packageName
                statusState.value = statusState.value - packageName
                connection.value = FloatingSession.active.value && clients.isNotEmpty()
            }
        }
        client.death = death
        binder.linkToDeath(death, 0)
        clients.put(packageName, client)?.let(::unlink)
        cached.remove(packageName)
        sessionState.value = sessionState.value - packageName
        statusState.value = statusState.value + (packageName to HostStatus("waiting_editor", ""))
        connection.value = FloatingSession.active.value
        scope.launch {
            operations.withLock {
                val control = FloatingSession.controlState()
                if (clients[packageName] === client) runCatching { remote.setActive(control.active, control.generation) }
            }
        }
        startPolling()
    }
    @Synchronized private fun startPolling() {
        if (FloatingSession.active.value && clients.isNotEmpty() && polling?.isActive != true) polling = scope.launch {
            while (isActive && FloatingSession.active.value && clients.isNotEmpty()) {
                operations.withLock {
                    val validated = mutableMapOf<String, Selection>()
                    val diagnostics = mutableMapOf<String, HostStatus>()
                    clients.forEach { (name, current) ->
                        if (!FloatingSession.active.value || SystemClock.elapsedRealtime() < current.retryAfter) return@forEach
                        try {
                            // Live selection is reserved for an explicit icon press. Background
                            // refreshes only validate an existing lease and never retarget it.
                            val state = current.remote.snapshot(false)
                            if (clients[name] !== current) return@forEach
                            current.failures = 0; current.retryAfter = 0
                            diagnostics[name] = HostStatus(state.getString("status").orEmpty().takeIf {
                                it in setOf("waiting_editor", "unsupported_mime", "pending_metadata", "editor_changed", "unfocused", "ready", "hooks_unavailable")
                            } ?: "waiting_editor", state.getString("host_version").orEmpty().take(40))
                            val selection = selection(name, state)
                            if (selection != null) {
                                validated[name] = selection
                                if (state.getBoolean("resumed")) cached[name] = selection to SystemClock.elapsedRealtime()
                                else cached.remove(name)
                            } else cached.remove(name)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: android.os.RemoteException) { snapshotFailed(name, current) }
                        catch (_: RuntimeException) { snapshotFailed(name, current) }
                    }
                    sessionState.value = if (FloatingSession.active.value) validated.filterKeys { clients.containsKey(it) } else emptyMap()
                    statusState.value = diagnostics.filterKeys { clients.containsKey(it) }
                }
                delay(400)
            }
        }
    }
    private fun snapshotFailed(name: String, client: Client) {
        cached.remove(name)
        sessionState.value = sessionState.value - name
        client.failures = minOf(client.failures + 1, 4)
        client.retryAfter = SystemClock.elapsedRealtime() + (1_000L shl (client.failures - 1))
    }
    private fun selection(packageName: String, state: Bundle): Selection? {
        val session = state.getString("session") ?: return null
        val age = SystemClock.elapsedRealtime() - state.getLong("created")
        if (age !in 0..NativeEditorSession.MAX_AGE_MS) return null
        val mimeTypes = state.getStringArray("mime_types")?.toSet().orEmpty()
        if (mimeTypes.isEmpty()) return null
        return Selection(packageName, session, mimeTypes)
    }
    @Synchronized internal fun clear() {
        clients.values.forEach(::unlink)
        clients.clear(); cached.clear(); polling?.cancel(); polling = null
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
            val name = packageName ?: cachedSelection(null)?.packageName ?: return@withLock null
            val client = clients[name] ?: return@withLock null
            try {
                val state = client.remote.snapshot(true)
                if (!FloatingSession.active.value || clients[name] !== client) return@withLock null
                client.failures = 0; client.retryAfter = 0
                val selected = selection(name, state)?.takeIf { state.getBoolean("resumed") }
                if (selected == null) {
                    cached.remove(name); sessionState.value = sessionState.value - name
                } else {
                    cached[name] = selected to SystemClock.elapsedRealtime()
                    sessionState.value = sessionState.value + (name to selected)
                }
                selected
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: android.os.RemoteException) { snapshotFailed(name, client); null }
            catch (_: RuntimeException) { snapshotFailed(name, client); null }
        }
    }
    suspend fun insert(context: Context, packageName: String, uri: Uri, mimeType: String, session: String): Boolean = withContext(Dispatchers.IO) {
        operations.withLock {
            if (!FloatingSession.active.value) return@withLock false
            val client = clients[packageName] ?: return@withLock false
            var granted = false
            var accepted = false
            try {
                val initial = selection(packageName, client.remote.snapshot(false))
                if (initial?.session != session || mimeType !in initial.mimeTypes) return@withLock false
                val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val uid = context.packageManager.getPackageUid(packageName, 0)
                // Grants are not reference-counted. Rolling back a second attempt must
                // not revoke the same URI from an attachment already held by this host.
                if (context.checkUriPermission(uri, -1, uid, read) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    context.grantUriPermission(packageName, uri, read)
                    granted = true
                }
                repeat(20) {
                    delay(100)
                    if (!FloatingSession.active.value || clients[packageName] !== client) return@withLock false
                    val state = client.remote.snapshot(false)
                    val current = selection(packageName, state)
                    if (current?.session != session || mimeType !in current.mimeTypes) return@withLock false
                    if (state.getBoolean("resumed")) {
                        // Poll only for host focus. Dispatch exactly once; never retry a send.
                        accepted = client.remote.insert(uri, mimeType, session, SystemClock.elapsedRealtimeNanos())
                        return@withLock accepted
                    }
                }
                false
            } catch (_: android.os.RemoteException) { false }
            catch (_: SecurityException) { false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { false }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
            finally {
                if (granted && !accepted) runCatching {
                    context.revokeUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                cached.remove(packageName)
                sessionState.value = sessionState.value - packageName
            }
        }
    }
}
