package one.njk.sao.moeview.data.net

import one.njk.sao.moeview.BooruConfig
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.ByteRange
import one.njk.sao.moeview.data.Transport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.*
import okhttp3.*
import kotlin.coroutines.resumeWithException

/** Board routing and request pacing. Extensions disable retries; SAO owns the read retry budget. */
class BooruHttp internal constructor(
    val config: BooruConfig,
    okHttpClientParam: OkHttpClient? = null,
    mediaOkHttpClientParam: OkHttpClient? = null,
    rateLimiters: Map<Board, RateLimiter>? = null,
) {
    private val transport = BooruTransport(config.useEdgeTransport, config.allowTransportFallback)
    private val limiters = rateLimiters ?: Board.entries.associateWith { RateLimiter(config.minRequestIntervalMs) }
    private val jsonClient = okHttpClientParam ?: newClient(config.readTimeoutMs)
    val okHttpClient = mediaOkHttpClientParam ?: newClient(config.mediaReadTimeoutMs)
        .newBuilder().addInterceptor(HotlinkRefererInterceptor).build()

    private fun newClient(readTimeout: Long) = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
        .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .dispatcher(Dispatcher().apply { maxRequests=config.maxRequests; maxRequestsPerHost=config.maxRequestsPerHost })
        .build()

    suspend fun fetch(board: Board, transports: List<Transport>? = null, buildUrl: (String) -> String): String {
        val candidates = transports ?: transport.candidatesFor(board)
        var last: Throwable = IOException("No transport")
        for (candidate in candidates) {
            val base = if(candidate==Transport.DIRECT) board.directBase else board.edgeBase ?: continue
            try {
                val result = fetchWithRetry(board,buildUrl(base))
                if(transports==null) transport.onSuccess(board,candidate)
                return result
            } catch(error: IOException) { currentCoroutineContext().ensureActive(); last=error }
        }
        if(transports==null) transport.onFailure(board,candidates.firstOrNull() ?: Transport.DIRECT)
        throw NoTransportException(board,last)
    }
    private suspend fun fetchWithRetry(board: Board,url: String): String {
        var attempt=0
        while(true) {
            limiters.getValue(board).acquire()
            val request=Request.Builder().url(url).header("User-Agent",config.userAgent)
                .header("Accept","application/json").apply { config.extraHeaders.forEach { (k,v) -> header(k,v) } }.build()
            try {
                return withContext(Dispatchers.IO) { coroutineScope {
                    val call=jsonClient.newCall(request)
                    val response=call.awaitResponse()
                    val closer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try { response.use { reply ->
                        if(!reply.isSuccessful) throw status(reply)
                        val body=reply.body ?: throw InvalidMediaResponseException()
                        if(body.contentLength()>MAX_JSON) throw InvalidMediaResponseException()
                        val out=ByteArrayOutputStream(); val buffer=ByteArray(16*1024)
                        body.byteStream().use { input -> while(true) {
                            ensureActive(); val count=input.read(buffer); if(count<0) break
                            if(out.size()+count>MAX_JSON) throw InvalidMediaResponseException()
                            out.write(buffer,0,count)
                        } }
                        out.toString(Charsets.UTF_8.name())
                    } } finally { closer.cancel() }
                } }
            } catch(error: IOException) {
                currentCoroutineContext().ensureActive()
                if(error is InvalidMediaResponseException || error is HttpStatusException && error.code !in setOf(429,500,502,503,504) || attempt>=config.maxRetriesPerTransport) throw error
                delay((error as? HttpStatusException)?.retryAfterMs?.takeIf { it>0 } ?: backoff(attempt)); attempt++
            }
        }
    }

    data class OpenedMedia(val response: Response,val cancel: () -> Unit)
    suspend fun openStream(board: Board,url: String,headers: Map<String,String>,range: ByteRange?): OpenedMedia {
        limiters.getValue(board).acquire()
        val request=Request.Builder().url(url).header("User-Agent",config.userAgent)
            .header("Accept","image/*,video/*,*/*").apply {
                headers.forEach { (k,v) -> header(k,v) }; config.extraHeaders.forEach { (k,v) -> header(k,v) }
                range?.let { header("Range",it.headerValue()) }
            }.build()
        val call=okHttpClient.newCall(request)
        return OpenedMedia(call.awaitResponse()) { call.cancel() }
    }
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call,error: IOException) { if(!continuation.isCancelled) continuation.resumeWithException(error) }
            override fun onResponse(call: Call,response: Response) {
                continuation.resume(response) { _,value,_ -> value.close() }
            }
        })
    }
    private fun backoff(attempt: Int) = (config.retryBaseDelayMs shl attempt).coerceAtMost(config.retryMaxDelayMs)+Random.nextLong(0,config.retryBaseDelayMs/2+1)
    fun close() {
        jsonClient.dispatcher.cancelAll(); jsonClient.connectionPool.evictAll()
        okHttpClient.dispatcher.cancelAll(); okHttpClient.connectionPool.evictAll()
    }
    companion object {
        private const val MAX_JSON=4*1024*1024
        fun status(response: Response) = HttpStatusException(response.code,
            response.header("Retry-After")?.toLongOrNull()?.takeIf { it in 0..86_400 }?.times(1000) ?: -1,
            response.header("Retry-After")?.take(128),response.header("cf-mitigated")=="challenge")
    }
}
internal object HotlinkRefererInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request=chain.request(); val host=request.url.host
        return chain.proceed(if(request.header("Referer")==null && (host=="gelbooru.com" || host.endsWith(".gelbooru.com")))
            request.newBuilder().header("Referer","https://gelbooru.com/").build() else request)
    }
}
