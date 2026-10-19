package one.njk.sao.extension.moeview

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.njk.sao.moeview.BooruApi
import one.njk.sao.moeview.BooruConfig
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.net.*
import one.njk.sao.source.ExtensionFailure
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** On-demand bounded client leases. Only HTTP pools/pacing are retained, never search results or files. */
class MoeviewRuntime private constructor(context: Context) {
    val profiles=MoeviewProfiles(context)
    private val lock=Mutex()
    private data class Entry(val api: BooruApi,var users: Int=0)
    private val clients=linkedMapOf<String,Entry>()
    private val gates=Board.entries.associateWith { RateLimiter(500) }
    internal var factory: (MoeviewConfig) -> BooruApi = ::create
    suspend fun <T> use(profile: MoeviewProfile,block: suspend (BooruApi) -> T): T {
        val key="${profile.id}:${profile.revision}"
        val entry=lock.withLock { clients.getOrPut(key) { Entry(factory(profile.config)) }.also { it.users++ } }
        try { return block(entry.api) }
        finally { withContext(NonCancellable) { lock.withLock {
            entry.users--
            while(clients.size>4) {
                val old=clients.entries.firstOrNull { it.value.users==0 } ?: break
                clients.remove(old.key); close(old.value.api)
            }
        } } }
    }
    suspend fun invalidate(id: String) { lock.withLock {
        clients.entries.filter { it.key.startsWith("$id:") }.map { it.key }.forEach { clients.remove(it)?.api?.let(::close) }
    } }
    internal fun close(api: BooruApi) {
        api.close()
        // Both production clients share this owned dispatcher. Eviction also releases idle callback threads.
        api.okHttpClient.dispatcher.executorService.shutdown()
    }
    private fun create(config: MoeviewConfig): BooruApi {
        val options=BooruConfig(userAgent="SAO-Moeview/1.0 (Android)",useEdgeTransport=config.proxy && !config.authenticated && config.clearance.isEmpty(),
            allowTransportFallback=false,maxRetriesPerTransport=0,defaultSfwMode=config.rating,mediaReadTimeoutMs=30_000)
        val base=OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequests=4; maxRequestsPerHost=2 })
            .retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(30,TimeUnit.SECONDS).callTimeout(75,TimeUnit.SECONDS)
        val json=base.followRedirects(false).addInterceptor { chain ->
            chain.proceed(MoeviewAuthentication.request(config,chain.request()))
        }.build()
        base.interceptors().clear()
        val media=base.followRedirects(true).addNetworkInterceptor { chain ->
            if(!allowedMedia(config.board,chain.request().url.toString())) throw InvalidMediaResponseException()
            chain.proceed(chain.request())
        }.build()
        return BooruApi.create(options,json,media,gates)
    }
    companion object {
        @Volatile private var instance: MoeviewRuntime?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: MoeviewRuntime(context.applicationContext).also { instance=it } }
        fun allowedMedia(board: Board,value: String): Boolean = runCatching {
            val url=value.toHttpUrl()
            val domains=when(board) { Board.YANDERE -> listOf("yande.re"); Board.KONACHAN -> listOf("konachan.com","konachan.net"); Board.GELBOORU -> listOf("gelbooru.com","moeview.app"); Board.DANBOORU -> listOf("donmai.us") }
            url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port==443 && domains.any { url.host==it || url.host.endsWith(".$it") }
        }.getOrDefault(false)
        fun failure(error: Exception): ExtensionFailure {
            if(error is ExtensionFailure) return error
            val cause=if(error is NoTransportException) error.cause else error
            return when(cause) {
                is HttpStatusException -> ExtensionFailure("HTTP_${cause.code}",cause.retryAfter)
                is InvalidMediaResponseException, is kotlinx.serialization.SerializationException, is org.json.JSONException -> ExtensionFailure("INVALID_RESPONSE")
                is javax.net.ssl.SSLException -> ExtensionFailure("TLS")
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> ExtensionFailure("TIMEOUT")
                is java.io.IOException -> ExtensionFailure("NETWORK")
                else -> ExtensionFailure("REQUEST_FAILED")
            }
        }
    }
}
