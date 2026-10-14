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

object NativeBridgeRegistry {
    data class Selection(val packageName: String, val session: String, val mimeTypes: Set<String>)
    private data class Client(val remote: INativeStickerClient, val binder: IBinder)
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

    @Synchronized internal fun register(packageName: String, remote: INativeStickerClient) {
        val binder = remote.asBinder()
        val client = Client(remote, binder)
        binder.linkToDeath({
            if (clients.remove(packageName, client)) {
                cached.remove(packageName)
                sessionState.value = sessionState.value - packageName
                statusState.value = statusState.value - packageName
                connection.value = clients.isNotEmpty()
            }
        }, 0)
        clients[packageName] = client
        cached.remove(packageName)
        sessionState.value = sessionState.value - packageName
        statusState.value = statusState.value + (packageName to HostStatus("waiting_editor", ""))
        connection.value = true
        if (polling?.isActive != true) polling = scope.launch {
            while (isActive && clients.isNotEmpty()) {
                operations.withLock {
                    val validated = mutableMapOf<String, Selection>()
                    val diagnostics = mutableMapOf<String, HostStatus>()
                    clients.forEach { (name, current) ->
                        try {
                            val state = current.remote.snapshot(true)
                            if (clients[name] !== current) return@forEach
                            diagnostics[name] = HostStatus(state.getString("status").orEmpty().takeIf {
                                it in setOf("waiting_editor", "unsupported_mime", "pending_metadata", "editor_changed", "unfocused", "ready", "hooks_unavailable")
                            } ?: "waiting_editor", state.getString("host_version").orEmpty().take(40))
                            val selection = selection(name, state)
                            if (selection != null) {
                                validated[name] = selection
                                if (state.getBoolean("resumed")) cached[name] = selection to SystemClock.elapsedRealtime()
                                else cached.remove(name)
                            } else cached.remove(name)
                        } catch (_: android.os.RemoteException) { cached.remove(name) }
                        catch (_: SecurityException) { cached.remove(name) }
                    }
                    sessionState.value = validated.filterKeys { clients.containsKey(it) }
                    statusState.value = diagnostics.filterKeys { clients.containsKey(it) }
                }
                delay(400)
            }
        }
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
        clients.clear(); cached.clear(); polling?.cancel(); polling = null
        sessionState.value = emptyMap(); statusState.value = emptyMap(); connection.value = false
    }
    fun available(packageName: String?) = packageName != null && clients[packageName]?.binder?.isBinderAlive == true
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
    suspend fun insert(context: Context, packageName: String, uri: Uri, mimeType: String, session: String): Boolean = withContext(Dispatchers.IO) {
        operations.withLock {
            val client = clients[packageName] ?: return@withLock false
            var granted = false
            var accepted = false
            try {
                val initial = selection(packageName, client.remote.snapshot(false))
                if (initial?.session != session || mimeType !in initial.mimeTypes) return@withLock false
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                granted = true
                repeat(20) {
                    delay(100)
                    if (clients[packageName] !== client) return@withLock false
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
