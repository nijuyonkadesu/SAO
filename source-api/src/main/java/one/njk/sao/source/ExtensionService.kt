package one.njk.sao.source

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Provider SDK lifecycle: capture UID before dispatch, cancel on caller death, bound payloads. */
abstract class ExtensionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    private val requests = ConcurrentHashMap<String, Job>()
    protected open fun failure(error: Exception) = ExtensionFailure("REQUEST_FAILED")
    protected abstract fun descriptor(): JSONObject
    protected abstract suspend fun execute(profile: String, operation: String, payload: JSONObject, caller: String): JSONObject
    protected open fun acceptsCaller(uid: Int): String? = packageManager.getPackagesForUid(uid).orEmpty().firstOrNull {
        packageManager.checkSignatures(packageName,it) == PackageManager.SIGNATURE_MATCH
    }
    private val binder = object : ISourceExtension.Stub() {
        override fun descriptor(): String {
            check(acceptsCaller(Binder.getCallingUid()) != null) { "Untrusted client" }
            return this@ExtensionService.descriptor().put("api",ExtensionProtocol.VERSION).toString()
        }
        override fun request(id: String, profile: String, operation: String, payload: String, callback: ISourceCallback) {
            val uid = Binder.getCallingUid()
            val caller = acceptsCaller(uid) ?: return
            if (!scope.isActive) { callback.failed(id,"{\"code\":\"DISCONNECTED\"}"); return }
            if (id.length > 64 || payload.length * 2 > ExtensionProtocol.MAX_REQUEST || requests.size >= 24) {
                callback.failed(id,"{\"code\":\"LIMIT_EXCEEDED\"}"); return
            }
            val key = "$uid:$id"
            val alive = java.util.concurrent.atomic.AtomicBoolean(true)
            val death = IBinder.DeathRecipient { alive.set(false); requests.remove(key)?.cancel() }
            try { callback.asBinder().linkToDeath(death,0) } catch (_: android.os.RemoteException) { return }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val response = withTimeout(85_000) { execute(profile,operation,JSONObject(payload),caller) }.toString()
                    require(response.length * 2 <= ExtensionProtocol.MAX_RESPONSE)
                    callback.complete(id,response)
                } catch (timeout: TimeoutCancellationException) {
                    try { callback.failed(id,"{\"code\":\"TIMEOUT\"}") } catch (_: android.os.RemoteException) { }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    try {
                        val failed = if (error is ExtensionFailure) error else failure(error)
                        callback.failed(id,JSONObject().put("code",failed.code).put("retryAfter",failed.retryAfter).toString())
                    }
                    catch (_: android.os.RemoteException) { }
                }
            }
            if (requests.putIfAbsent(key,job) == null) {
                job.invokeOnCompletion { cause ->
                    // Even a lazy request cancelled before execute() starts must finish its reply.
                    // A host-cancelled request ignores this; a live caller can rebind promptly.
                    if (cause is CancellationException) {
                        try { callback.failed(id,"{\"code\":\"DISCONNECTED\"}") } catch (_: android.os.RemoteException) { }
                    }
                    requests.remove(key,job)
                    try { callback.asBinder().unlinkToDeath(death,0) } catch (_: java.util.NoSuchElementException) { }
                }
                if (alive.get()) job.start() else job.cancel()
            } else {
                job.cancel()
                try { callback.asBinder().unlinkToDeath(death,0) } catch (_: java.util.NoSuchElementException) { }
            }
        }
        override fun cancel(id: String) { requests.remove("${Binder.getCallingUid()}:$id")?.cancel() }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
