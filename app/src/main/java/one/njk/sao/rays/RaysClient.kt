package one.njk.sao.rays

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import androidx.core.net.toUri
import com.skyd.rays.api.IApiService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** IPC contract only. Rays' database, models and implementation stay in the installed app. */
class RaysClient(private val context: Context, private val packageName: String,
    private val io: CoroutineDispatcher) {
    suspend fun search(keyword: String, start: Int = 0, size: Int = PAGE_SIZE): RaysPage = withContext(io) {
        require(start >= 0 && size in 1..100)
        if (!installed(context, packageName)) throw RaysException(RaysProblem.NOT_INSTALLED)
        val connected = CompletableDeferred<IApiService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(IApiService.Stub.asInterface(service))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(RaysException(RaysProblem.DISCONNECTED))
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        var bound = false
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(packageName,
                "com.skyd.rays.api.ApiService")), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw RaysException(RaysProblem.UPDATE_REQUIRED)
            val service = try { withTimeout(5_000) { connected.await() } }
                catch (_: TimeoutCancellationException) { throw RaysException(RaysProblem.DISCONNECTED) }
            // Synchronous Binder calls run on IO. There is no idle binding or retry loop.
            val result = service.searchStickersPage(context.packageName, keyword, start, size)
                ?: throw RaysException(RaysProblem.ACCESS_DENIED)
            coroutineContext.ensureActive()
            parse(result, packageName, start, size)
        } catch (failure: SecurityException) {
            throw RaysException(RaysProblem.ACCESS_DENIED, failure)
        } catch (failure: DeadObjectException) {
            throw RaysException(RaysProblem.DISCONNECTED, failure)
        } catch (failure: RemoteException) {
            throw RaysException(RaysProblem.UPDATE_REQUIRED, failure)
        } finally {
            if (bound) context.unbindService(connection)
        }
    }

    companion object {
        const val PACKAGE = "com.skyd.rays"
        const val DEBUG_PACKAGE = "com.skyd.rays.debug"
        const val PAGE_SIZE = 60
        val packages = listOf(PACKAGE, DEBUG_PACKAGE)
        fun installed(context: Context, packageName: String): Boolean = try {
            require(packageName in packages)
            context.packageManager.getApplicationInfo(packageName, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) { false }

        internal fun parse(value: String, packageName: String, start: Int, size: Int): RaysPage {
            try {
                val json = JSONObject(value)
                val data = json.getJSONArray("data")
                require(json.getInt("startIndex") == start && json.getInt("size") == data.length())
                require(data.length() <= size)
                val next = if (json.isNull("nextIndex")) null else json.getInt("nextIndex")
                require(next == null || data.length() > 0 && next.toLong() == start.toLong() + data.length())
                return RaysPage(List(data.length()) { index ->
                    val row = data.getJSONObject(index)
                    val sticker = row.getJSONObject("sticker")
                    val uri = row.getString("uri").toUri()
                    require(uri.scheme == "content" && uri.authority == "$packageName.fileprovider")
                    val mime = row.getString("mimeType").let { if (it == "image/jpg") "image/jpeg" else it }
                    require(mime.startsWith("image/") && mime != "image/*")
                    val tags = row.getJSONArray("tags")
                    RaysSticker(sticker.getString("uuid"), sticker.getString("title"), uri, mime,
                        sticker.getString("stickerMd5"), List(tags.length()) { tags.getJSONObject(it).getString("tag") })
                }, next)
            } catch (failure: IllegalArgumentException) {
                throw RaysException(RaysProblem.INVALID_RESPONSE, failure)
            } catch (failure: org.json.JSONException) {
                throw RaysException(RaysProblem.INVALID_RESPONSE, failure)
            }
        }
    }
}

data class RaysSticker(val id: String, val title: String, val uri: Uri, val mimeType: String,
    val revision: String, val tags: List<String>)
data class RaysPage(val items: List<RaysSticker>, val nextIndex: Int?)
enum class RaysProblem { NOT_INSTALLED, UPDATE_REQUIRED, ACCESS_DENIED, DISCONNECTED, INVALID_RESPONSE }
class RaysException(val problem: RaysProblem, cause: Throwable? = null) : IOException(problem.name, cause)
