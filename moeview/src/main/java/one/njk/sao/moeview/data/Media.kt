package one.njk.sao.moeview.data

import java.io.Closeable
import java.io.InputStream
import okhttp3.Response

/**
 * What kind of media a post points at. Detected from the file extension
 * (explicit `file_ext` when the board provides it, otherwise parsed from the
 * URL), because none of the boards return a reliable MIME type in their JSON.
 *
 * - [IMAGE] — jpg/png/webp/avif/... (including animated WebP, which most image
 *   loaders play automatically).
 * - [GIF] — animated GIF; load with an image loader that supports animation.
 * - [VIDEO] — mp4/webm/...; play with a streaming player, **never** download
 *   the whole file into memory.
 */
enum class MediaType {
    IMAGE,
    GIF,
    VIDEO;

    val isVideo: Boolean
        get() = this == VIDEO

    val isAnimatedImage: Boolean
        get() = this == GIF

    companion object {
        private val VIDEOS =
            setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "mpeg", "mpg", "flv", "wmv", "ts")
        private val GIFS = setOf("gif")

        fun fromExtension(extension: String?): MediaType {
            val ext = extension?.removePrefix(".")?.lowercase()?.takeIf { it.isNotBlank() } ?: return IMAGE
            return when {
                ext in VIDEOS -> VIDEO
                ext in GIFS -> GIF
                else -> IMAGE
            }
        }

        /** Extract the lowercase extension from a URL path (query/fragment ignored). */
        fun extensionOf(url: String?): String? {
            val u = url ?: return null
            val path = u.substringBefore('?').substringBefore('#')
            val name = path.substringAfterLast('/')
            if (!name.contains('.')) return null
            return name.substringAfterLast('.').takeIf { it.isNotBlank() }
        }

        /**
         * Best-effort detection: use [fileExtension] when the board gives one,
         * else fall back to the first URL that has a recognizable extension.
         * Always returns a value ([IMAGE] when nothing matches).
         */
        fun detect(fileExtension: String?, vararg urls: String?): MediaType {
            val ext = fileExtension?.takeIf { it.isNotBlank() } ?: urls.firstNotNullOfOrNull { extensionOf(it) }
            return fromExtension(ext)
        }
    }
}

/** Which size/quality variant of a post's media to resolve. */
enum class MediaKind {
    /** Small grid tile (thumbnail). */
    PREVIEW,

    /** Downscaled full image / video sample; falls back to [FULL] when absent. */
    SAMPLE,

    /** The original file (for videos this is the playable media file). */
    FULL,
}

/**
 * A single HTTP byte range, e.g. `Range: bytes=0-1023`.
 * [endInclusive] `null` means "from [start] to the end".
 */
data class ByteRange(val start: Long, val endInclusive: Long? = null) {
    fun headerValue(): String =
        if (endInclusive == null) "bytes=$start-" else "bytes=$start-$endInclusive"

    init {
        require(start >= 0) { "start must be >= 0" }
        if (endInclusive != null) require(endInclusive >= start) { "end must be >= start" }
    }

    companion object {
        /** First byte only — cheap way to probe range support (`206` vs `200`). */
        val FIRST_BYTE = ByteRange(0, 0)
    }
}

/**
 * Everything a player/loader needs to fetch one piece of media: the resolved
 * URL plus the headers it must send (e.g. Gelbooru's hotlink `Referer`).
 *
 * Feed [url] to an `OkHttpDataSource`-backed ExoPlayer and pass [headers] via
 * `setDefaultRequestProperties` (or [BooruApi.openMedia] to stream directly).
 */
data class MediaRequest(
    val url: String,
    val headers: Map<String, String>,
    val mediaType: MediaType,
    /** True when [url] is a direct CDN URL that supports HTTP Range (seekable). */
    val supportsSeeking: Boolean,
)

/**
 * A live, un-buffered media response. The body is streamed from the socket —
 * nothing is read into memory, so this is safe for large videos.
 *
 * Always [close] it (or use `use { }`). Example:
 * ```kotlin
 * api.openMedia(Board.GELBOORU, post, MediaKind.FULL, ByteRange(0, 1 shl 20)).use { media ->
 *     media.byteStream().copyTo(outputStream)
 * }
 * ```
 */
class BooruMediaStream internal constructor(
    private val response: Response,
    val mediaType: MediaType,
    val requestUrl: String,
    val rangeRequested: ByteRange?,
    private val cancel: () -> Unit,
) : Closeable {

    val statusCode: Int
        get() = response.code

    val contentType: String?
        get() = response.header("Content-Type")

    /** Bytes the server says it will send (`-1` when unknown / chunked). */
    val contentLength: Long
        get() = response.body?.contentLength() ?: -1L

    /** Server advertised `Accept-Ranges: bytes` (advisory — the edge lies). */
    val acceptsRanges: Boolean
        get() = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true

    /**
     * True when the server answered a Range request with `206 Partial Content`.
     * This is the only reliable signal that seeking works; the moeview edge
     * advertises `Accept-Ranges` but replies `200` and ignores the Range header.
     */
    val rangeHonored: Boolean
        get() = response.code == 206

    /** Raw socket stream. Caller-side reads are progressive. */
    fun byteStream(): InputStream = response.body!!.byteStream()

    fun cancel() = cancel.invoke()
    override fun close() { cancel(); response.close() }
}
