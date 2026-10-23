package one.njk.sao.source

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExtensionFailure(override val code: String, val retryAfter: String? = null) :
    SourceException(code = code, message = "Source extension: $code")

/** One bounded, cancellable request. No idle binding or automatic reconnect loop. */
class ExtensionClient(private val context: Context, val component: ComponentName) {
    /** Profile discovery is paged too, so adding profiles never overflows one Binder response. */
    suspend fun profiles(): List<ExtensionProfile> {
        val result = mutableListOf<ExtensionProfile>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val reply = request(operation=ExtensionProtocol.PROFILES,payload=JSONObject().put("cursor",cursor))
            val rows = reply.getJSONArray("profiles")
            if(rows.length()>100) throw ExtensionFailure("INVALID_RESPONSE")
            repeat(rows.length()) { result += SourceWire.profile(rows.getJSONObject(it)) }
            cursor = SourceWire.nullable(reply,"next")
            if(cursor != null && !seen.add(cursor)) throw ExtensionFailure("INVALID_RESPONSE")
        } while(cursor != null)
        if(result.map { it.id }.distinct().size != result.size) throw ExtensionFailure("INVALID_RESPONSE")
        return result
    }
    suspend fun request(profile: String = "", operation: String, payload: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        SourceAccess.requireTarget(context,component.packageName)
        val service = try { context.packageManager.getServiceInfo(component,android.content.pm.PackageManager.GET_META_DATA) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { throw ExtensionFailure("UNAVAILABLE") }
        if (service.metaData?.getInt(ExtensionProtocol.API_VERSION) != ExtensionProtocol.VERSION) throw ExtensionFailure("INCOMPATIBLE")
        require(payload.toString().length * 2 <= ExtensionProtocol.MAX_REQUEST)
        val ready = CompletableDeferred<ISourceExtension>()
        var pending: CompletableDeferred<JSONObject>? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(ISourceExtension.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) {
                val failure = ExtensionFailure("DISCONNECTED")
                ready.completeExceptionally(failure); pending?.completeExceptionally(failure)
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        var bound = false
        var remote: ISourceExtension? = null
        val id = UUID.randomUUID().toString()
        try {
            bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw ExtensionFailure("UNAVAILABLE")
            remote = withTimeout(5_000) { ready.await() }
            val result = CompletableDeferred<JSONObject>(); pending = result
            remote.request(id, profile, operation, payload.toString(), object : ISourceCallback.Stub() {
                override fun complete(requestId: String, response: String) {
                    if (requestId != id || !result.isActive) return
                    try {
                        require(response.length * 2 <= ExtensionProtocol.MAX_RESPONSE)
                        result.complete(JSONObject(response))
                    } catch (_: Exception) { result.completeExceptionally(ExtensionFailure("INVALID_RESPONSE")) }
                }
                override fun failed(requestId: String, code: String) {
                    if (requestId == id) {
                        val failure = try { JSONObject(code).let { ExtensionFailure(it.getString("code").take(64),SourceWire.nullable(it,"retryAfter")) } }
                            catch (_: Exception) { ExtensionFailure("REQUEST_FAILED") }
                        result.completeExceptionally(failure)
                    }
                }
            })
            withTimeout(if(operation==ExtensionProtocol.IMPORT_MEDIA) 8*60_000L+5_000 else 90_000L) { result.await() }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ExtensionFailure("TIMEOUT")
        } catch (_: SecurityException) { throw ExtensionFailure("UNTRUSTED") }
        catch (_: android.os.RemoteException) { throw ExtensionFailure("DISCONNECTED") }
        finally {
            pending?.cancel()
            try { remote?.cancel(id) } catch (_: android.os.RemoteException) { }
            if (bound) context.unbindService(connection)
        }
    }
}
