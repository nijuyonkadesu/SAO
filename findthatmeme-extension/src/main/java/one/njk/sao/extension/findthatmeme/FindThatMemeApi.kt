package one.njk.sao.extension.findthatmeme

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import one.njk.sao.source.ExtensionFailure
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** One cancellable request at a time per operation; retry and pagination policy belong to SAO. */
internal class FindThatMemeApi(internal val http: OkHttpClient = client()) {
    suspend fun search(text: String, offset: Int): JSONArray {
        require(text.length <= 2048 && offset >= 0)
        val body = JSONObject().put("search", text).put("offset", offset).toString()
        val request = Request.Builder().url("https://findthatmeme.com/api/v1/search")
            .header("X-CSRF-Validation-Header", "true")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return coroutineScope {
            open(request).use { opened ->
                // Header delivery ends the await above; cancellation must also interrupt a slow body.
                val cancel = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { opened.cancel() }
                }
                try {
                    val body = requireNotNull(opened.response.body)
                    val source = body.source()
                    if (body.contentLength() > JSON_LIMIT || source.request(JSON_LIMIT + 1)) {
                        throw ExtensionFailure("INVALID_RESPONSE")
                    }
                    try {
                        JSONArray(source.readUtf8()).also {
                            if (it.length() > PAGE_SIZE) throw ExtensionFailure("INVALID_RESPONSE")
                        }
                    } catch (_: JSONException) { throw ExtensionFailure("INVALID_RESPONSE") }
                } finally { cancel.cancel() }
            }
        }
    }

    suspend fun media(url: String): Opened {
        if (!allowedMedia(url)) throw ExtensionFailure("INVALID_RESPONSE")
        return open(Request.Builder().url(url).build())
    }

    private suspend fun open(request: Request): Opened {
        val call = http.newCall(request.newBuilder().header("User-Agent", USER_AGENT).build())
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        if (!response.isSuccessful) {
            val failure = ExtensionFailure("HTTP_${response.code}", response.header("Retry-After")?.take(128))
            response.close()
            throw failure
        }
        return Opened(response, call)
    }

    internal class Opened(val response: Response, private val call: Call) : AutoCloseable {
        fun cancel() = call.cancel()
        override fun close() = response.close()
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val JSON_LIMIT = 2L * 1024 * 1024
        private const val USER_AGENT = "SAO-FindThatMeme/1.0 (+https://github.com/nijuyonkadesu/SAO)"
        private const val MEDIA_BASE = "https://s3.thehackerblog.com/findthatmeme/"

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 })
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).build()

        fun allowedMedia(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false
            return url.isHttps && url.host == "s3.thehackerblog.com" && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
                url.pathSegments.firstOrNull() == "findthatmeme" &&
                url.pathSegments.size in 2..3 && url.pathSegments.none { it.isBlank() || '/' in it || '\\' in it }
        }

        fun mediaUrl(value: String, thumbnail: Boolean = false): String? {
            if (value.isBlank() || value.length > 1200) return null
            val url = if (value.startsWith("https://")) value else {
                // Only a filename is accepted as a relative API path, never an arbitrary URL.
                if (!Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+").matches(value)) return null
                MEDIA_BASE + (if (thumbnail) "thumb/" else "") + value
            }
            return url.takeIf(::allowedMedia)
        }

        fun mime(url: String): String? = when (url.toHttpUrl().pathSegments.last().substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            else -> null
        }

        /** Headers on the public bucket often say octet-stream; bytes decide the real format. */
        fun matches(bytes: ByteArray, mime: String): Boolean {
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
            fun ascii(offset: Int, text: String) = bytes.size >= offset + text.length && text.indices.all { bytes[offset + it].toInt() == text[it].code }
            return when (mime) {
                "image/jpeg" -> starts(0xff, 0xd8, 0xff)
                "image/png" -> starts(137, 80, 78, 71, 13, 10, 26, 10)
                "image/gif" -> ascii(0, "GIF87a") || ascii(0, "GIF89a")
                "image/webp" -> ascii(0, "RIFF") && ascii(8, "WEBP")
                "video/mp4" -> ascii(4, "ftyp")
                "video/webm" -> starts(0x1a, 0x45, 0xdf, 0xa3)
                else -> false
            }
        }
    }
}
