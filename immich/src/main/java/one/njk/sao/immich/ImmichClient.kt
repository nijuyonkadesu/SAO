package one.njk.sao.immich

import okhttp3.ResponseBody

import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class ImmichConnection(serverUrl: String, internal val apiKey: String) {
    val apiUrl: HttpUrl
    init {
        require(apiKey.isNotBlank() && apiKey.none { it == '\r' || it == '\n' }) { "An API key is required" }
        val url = serverUrl.trim().toHttpUrl()
        require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() && url.query == null && url.fragment == null) {
            "Use a server URL without credentials, query parameters or fragments"
        }
        val path = url.encodedPath.trimEnd('/')
        apiUrl = url.newBuilder().encodedPath(if (path.endsWith("/api")) "$path/" else "$path/api/").build()
    }
    override fun toString() = "ImmichConnection(apiUrl=$apiUrl, apiKey=<redacted>)"
}

class ImmichClient(val connection: ImmichConnection) : AutoCloseable {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        // No cross-host credential forwarding, HTTP downgrade, or ambiguous mutation retries.
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val url = chain.request().url
            check(url.scheme == connection.apiUrl.scheme && url.host == connection.apiUrl.host && url.port == connection.apiUrl.port)
            chain.proceed(chain.request().newBuilder().header("x-api-key", connection.apiKey).build())
        }.build()
    // Read-only POST searches are safe to repeat too. Writes retain the base client's policy.
    private val readHttp = http.newBuilder().retryOnConnectionFailure(true).build()
    private val readPostPaths = setOf("search/metadata", "search/smart", "search/random", "assets/bulk-upload-check")
        .map { connection.apiUrl.resolve(it)!!.encodedPath }.toSet()
    private val calls = object : Call.Factory {
        override fun newCall(request: Request): Call {
            val readOnly = request.method == "GET" || request.method == "HEAD" ||
                (request.method == "POST" && request.url.encodedPath in readPostPaths)
            return (if (readOnly) readHttp else http).newCall(request)
        }
    }
    private val moshi = Moshi.Builder().add(object : JsonAdapter.Factory {
        override fun create(type: java.lang.reflect.Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? =
            if (type == DateEqualityFilter::class.java && annotations.isEmpty())
                // The outer nullSafe keeps an absent filter omitted; only its eq may be null.
                moshi.nextAdapter<DateEqualityFilter>(this, type, annotations).serializeNulls().nullSafe()
            else null
    }).addLast(KotlinJsonAdapterFactory()).build()
    val api: ImmichApi = Retrofit.Builder().baseUrl(connection.apiUrl).callFactory(calls)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build().create(ImmichApi::class.java)

    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun patch(fields: Map<String, Any?>) = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
        .serializeNulls().toJson(fields).toRequestBody("application/json".toMediaType())
    suspend fun updateAsset(id: String, update: AssetUpdate) = api.updateAssetFields(id, patch(update.fields()))
    suspend fun updateAlbum(id: String, update: AlbumUpdate) = api.updateAlbumFields(id, patch(update.fields()))
    suspend fun updateTag(id: String, update: TagUpdate) = api.updateTagFields(id, patch(update.fields()))

    /** Streaming multipart; no bitmap decoding/recompression and no whole-file heap allocation. */
    suspend fun upload(file: File, mimeType: String, created: Instant, modified: Instant): UploadResult {
        require(file.isFile)
        val text = "text/plain".toMediaType()
        return api.upload(MultipartBody.Part.createFormData("assetData", file.name, file.asRequestBody(mimeType.toMediaType())),
            created.toString().toRequestBody(text), modified.toString().toRequestBody(text))
    }

    /** Fetch into caller-owned storage atomically. Streaming reads are bounded and cancellable. */
    suspend fun download(id: String, destination: File, preview: Boolean = false,
        edited: Boolean = true, maxBytes: Long = 64L * 1024 * 1024): File = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        destination.parentFile?.mkdirs()
        val body = if (preview) api.thumbnail(id, edited = edited) else api.original(id, edited)
        saveDownload(body, destination, maxBytes)
    }
    suspend fun downloadPerson(id: String, destination: File) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        saveDownload(api.personThumbnail(id), destination, 2L * 1024 * 1024)
    }
    private suspend fun saveDownload(body: ResponseBody, destination: File, maxBytes: Long): File {
        val staging = try { File.createTempFile("immich-", ".pending", destination.parentFile) }
            catch (error: Exception) { body.close(); throw error }
        try {
            coroutineScope { body.use {
                // Retrofit's @Streaming returns before the body is read. Close that stream when
                // this coroutine is cancelled so a slow transfer cannot hold an IO worker.
                val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { body.close() }
                }
                try {
                require(it.contentLength() <= maxBytes) { "Media exceeds download limit" }
                it.byteStream().use { input -> staging.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024); var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= maxBytes) { "Media exceeds download limit" }
                        output.write(buffer, 0, count)
                    }
                } }
                } finally { closer.cancel() }
            } }
            currentCoroutineContext().ensureActive()
            check(staging.renameTo(destination)) { "Unable to save media" }
            return destination
        } finally { staging.delete() }
    }
}
