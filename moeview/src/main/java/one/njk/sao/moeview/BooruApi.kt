package one.njk.sao.moeview

import one.njk.sao.moeview.data.ApiKind
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.BooruMediaStream
import one.njk.sao.moeview.data.ByteRange
import one.njk.sao.moeview.data.MediaKind
import one.njk.sao.moeview.data.MediaRequest
import one.njk.sao.moeview.data.MediaType
import one.njk.sao.moeview.data.Post
import one.njk.sao.moeview.data.SfwMode
import one.njk.sao.moeview.data.Tag
import one.njk.sao.moeview.data.TagCategory
import one.njk.sao.moeview.data.Transport
import one.njk.sao.moeview.data.dto.parsePosts
import one.njk.sao.moeview.data.dto.parseTags
import one.njk.sao.moeview.data.net.BooruHttp
import one.njk.sao.moeview.data.net.HttpStatusException
import one.njk.sao.moeview.data.net.NoTransportException
import one.njk.sao.moeview.data.net.InvalidMediaResponseException
import one.njk.sao.moeview.data.net.RateLimiter
import one.njk.sao.moeview.data.ratingClause
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Public entry point: search posts, autocomplete tags/categories, and resolve /
 * stream images, GIFs and videos for any of the four boards, talking to
 * moeview.app's edges and the board APIs.
 *
 * ```kotlin
 * val api = BooruApi.create()          // or BooruApi.create(BooruConfig(...))
 *
 * val posts = api.search(Board.GELBOORU, tags = "blue_archive")
 * val cats  = api.tags(Board.KONACHAN, prefix = "blue")
 *
 * // Images / GIFs (grid + detail):
 * val thumb = api.mediaUrl(Board.GELBOORU, posts[0], MediaKind.PREVIEW)
 *
 * // Video: stream it, don't download it. Range reads are honored on the CDN.
 * api.openMedia(Board.GELBOORU, posts[1], MediaKind.FULL, ByteRange(0, 1 shl 20)).use { media ->
 *     media.byteStream().copyTo(sink)
 * }
 *
 * api.close()                          // when the app is done with it
 * ```
 *
 * All methods are `suspend` and may throw [HttpStatusException] (non-2xx) or
 * [NoTransportException] (every transport for that board failed).
 */
class BooruApi internal constructor(
    private val http: BooruHttp,
    private val json: Json = DEFAULT_JSON,
) {

    val config: BooruConfig
        get() = http.config

    /** Unbounded media client (hotlink Referer injected). Share with Coil / Media3. */
    val okHttpClient: OkHttpClient
        get() = http.okHttpClient

    // -------------------------------------------------------------------------
    // Posts & tags
    // -------------------------------------------------------------------------

    /**
     * Fetch a page of posts.
     *
     * @param tags space-separated tag query (`""` = board default / recent).
     *   Prefix a tag with `-` to exclude. A `rating:` term you pass here is kept
     *   as-is and the [sfwMode] filter is skipped.
     * @param sfwMode content segregation for this call, defaults to
     *   [config.defaultSfwMode] (SFW). Implemented as a rating filter the
     *   boards can actually express: SFW = `rating:<safe>`, NSFW = the negation
     *   `-rating:<safe>`, ALL = no filter.
     * @param page 1-based page number; mapped to the right parameter per dialect
     *   (moebooru/Danbooru `page`, Gelbooru `pid` 0-based).
     */
    suspend fun search(
        board: Board,
        tags: String = "",
        page: Int = 1,
        limit: Int = board.maxPageSize,
        sfwMode: SfwMode = config.defaultSfwMode,
    ): List<Post> {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, board.maxPageSize)
        val effectiveTags = applySfw(tags, board.apiKind, sfwMode)
        // A safe-only edge can't serve NSFW/ALL: skip it so we never silently
        // return safe content for an explicit query.
        val transports =
            if (sfwMode != SfwMode.SFW && board.edgeIsSfwOnly) listOf(Transport.DIRECT) else null
        val body =
            http.fetch(board, transports) { base -> postUrl(base, board, effectiveTags, safePage, safeLimit) }
        return parsePosts(board.apiKind, body, json)
    }

    /** Look up a single post by numeric id, or `null` if it does not exist. */
    suspend fun post(board: Board, id: Int): Post? {
        val body =
            try {
                http.fetch(board) { base -> postByIdUrl(base, board, id) }
            } catch (e: HttpStatusException) {
                if (e.code == 404 || e.code == 400) return null else throw e
            }
        return parsePosts(board.apiKind, body, json).firstOrNull()
    }

    /**
     * Tag autocomplete, ordered by post count (most used first). Boorus do not
     * rate tags, so SFW segregation cannot apply to tag *names*; filter by the
     * [Tag.category]/[TagCategory] (general / artist / character / copyright /
     * metadata) and optionally list [exclude]d tag names (augmented by
     * [config.nsfwTagBlocklist] when you supply one).
     *
     * @param prefix partial tag name; blank returns an empty list without a request.
     * @param categories keep only these tag categories (null = all).
     * @param exclude tag names to drop from the results.
     */
    suspend fun tags(
        board: Board,
        prefix: String,
        limit: Int = 20,
        categories: Set<TagCategory>? = null,
        exclude: Set<String> = emptySet(),
    ): List<Tag> {
        if (prefix.isBlank()) return emptyList()
        val body = http.fetch(board) { base -> tagUrl(base, board, prefix, limit.coerceIn(1, 50)) }
        val blocked = config.nsfwTagBlocklist + exclude
        return parseTags(board.apiKind, body, json)
            .filter { tag ->
                (categories == null || tag.category in categories) &&
                    blocked.none { it.equals(tag.name, ignoreCase = true) }
            }
            .take(limit.coerceIn(1, 50))
    }

    /**
     * Look up the metadata (post count, category) for one exact tag name,
     * e.g. a pinned home-page tag. `null` when the tag does not exist.
     */
    suspend fun tag(board: Board, name: String): Tag? {
        if (name.isBlank()) return null
        val body = http.fetch(board) { base -> tagUrl(base, board, name, 25, exact = true) }
        return parseTags(board.apiKind, body, json)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    // -------------------------------------------------------------------------
    // Media: URL resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve a media URL for a post, choosing the best transport for the media
     * kind:
     *  - **video** → the direct CDN URL (Range-capable; the moeview edge is not),
     *    with [Board.mediaReferer] applied by [okHttpClient];
     *  - **image / GIF** → the moeview edge when the board has one (hotlink-safe),
     *    else the direct URL.
     *
     * For videos this returns the *playable* file (use a streaming player);
     * [MediaKind.PREVIEW] on a video post returns the still thumbnail, which is
     * what a grid should load.
     *
     * Returns `null` when the requested variant does not exist.
     */
    fun mediaUrl(board: Board, post: Post, kind: MediaKind): String? =
        mediaCandidates(board, post, kind).firstOrNull()?.url

    /**
     * Everything needed to play/fetch this media: URL + required headers + type.
     * Handy for ExoPlayer's `OkHttpDataSource.setDefaultRequestProperties`.
     */
    fun mediaRequest(board: Board, post: Post, kind: MediaKind = MediaKind.FULL): MediaRequest? {
        val candidate = mediaCandidates(board, post, kind).firstOrNull() ?: return null
        return MediaRequest(
            url = candidate.url,
            headers = candidate.headers,
            mediaType = variantMediaType(post, kind),
            supportsSeeking = candidate.transport == Transport.DIRECT,
        )
    }

    /** Headers the direct CDN requires for this board (Gelbooru: hotlink `Referer`). */
    fun mediaHeaders(board: Board): Map<String, String> =
        board.mediaReferer?.let { mapOf("Referer" to it) } ?: emptyMap()

    // -------------------------------------------------------------------------
    // Media: streaming
    // -------------------------------------------------------------------------

    /**
     * Open an **unbuffered** media stream. Nothing is downloaded into memory;
     * read [BooruMediaStream.byteStream] progressively (video playback) and
     * [close][BooruMediaStream.close] it when done.
     *
     * @param range optional `Range: bytes=…`. Passing a range is how you seek in
     *   a video: the direct CDN replies `206`; the edge ignores it and replies
     *   `200` (see [BooruMediaStream.rangeHonored]).
     */
    suspend fun openMedia(
        board: Board,
        post: Post,
        kind: MediaKind = MediaKind.FULL,
        range: ByteRange? = null,
    ): BooruMediaStream {
        val candidates = mediaCandidates(board, post, kind)
        var lastError: Throwable = IOException("no media URL for post ${post.id}")

        for (candidate in candidates) {
            try {
                val opened = http.openStream(board, candidate.url, candidate.headers, range)
                val response = opened.response
                val contentType = response.header("Content-Type")
                if (response.isSuccessful && isMediaContentType(contentType)) {
                    return BooruMediaStream(
                        response = response,
                        mediaType = variantMediaType(post, kind),
                        requestUrl = candidate.url,
                        rangeRequested = range,
                        cancel = opened.cancel,
                    )
                }
                // e.g. a hotlink page (text/html) or an error body: try the next transport.
                response.close()
                lastError = if(!response.isSuccessful) BooruHttp.status(response) else InvalidMediaResponseException()
            } catch (e: IOException) {
                lastError = e
            }
        }

        throw NoTransportException(board, lastError)
    }

    /** Release the underlying HTTP resources. */
    fun close() = http.close()

    // -------------------------------------------------------------------------
    // Media transport selection
    // -------------------------------------------------------------------------

    private data class MediaCandidate(
        val transport: Transport,
        val url: String,
        val headers: Map<String, String>,
    )

    private fun mediaCandidates(board: Board, post: Post, kind: MediaKind): List<MediaCandidate> {
        val directUrl = directMediaUrl(post, kind) ?: return emptyList()
        return mediaTransportOrder(board, variantMediaType(post, kind)).mapNotNull { transport ->
            when (transport) {
                Transport.DIRECT ->
                    MediaCandidate(Transport.DIRECT, directUrl, mediaHeaders(board))
                Transport.EDGE ->
                    edgeMediaUrl(board, directUrl)?.let {
                        MediaCandidate(Transport.EDGE, it, emptyMap())
                    }
            }
        }
    }

    /**
     * The media type of the *resolved variant*. A video's [MediaKind.PREVIEW] is
     * a still thumbnail (an image), so it should travel the image route.
     */
    private fun variantMediaType(post: Post, kind: MediaKind): MediaType =
        when (kind) {
            MediaKind.PREVIEW -> MediaType.IMAGE
            MediaKind.SAMPLE, MediaKind.FULL -> post.mediaType
        }

    /**
     * Video seeks, so prefer the direct CDN (Range-capable) and keep the edge
     * only as a progressive fallback. Still images/GIFs don't seek, so prefer
     * the hotlink-safe edge and fall back to the direct CDN.
     */
    private fun mediaTransportOrder(board: Board, mediaType: MediaType): List<Transport> {
        val hasEdge = board.hasMediaEdge && config.useEdgeTransport
        if (!hasEdge) return listOf(Transport.DIRECT)
        val base =
            if (mediaType == MediaType.VIDEO) {
                listOf(Transport.DIRECT, Transport.EDGE)
            } else {
                listOf(Transport.EDGE, Transport.DIRECT)
            }
        return if (config.allowTransportFallback) base else base.take(1)
    }

    private fun directMediaUrl(post: Post, kind: MediaKind): String? =
        when (kind) {
            MediaKind.PREVIEW -> post.previewUrl
            MediaKind.SAMPLE -> post.sampleUrl ?: post.fileUrl
            MediaKind.FULL -> post.fileUrl
        }

    private fun edgeMediaUrl(board: Board, directUrl: String): String? {
        val edgeBase = board.mediaEdgeBase ?: return null
        return gelbooruEdgeUrl(edgeBase, directUrl)
    }

    private fun isMediaContentType(contentType: String?): Boolean {
        if (contentType == null) return true // some CDNs omit it; trust the URL
        val ct = contentType.lowercase()
        return ct.startsWith("image/") ||
            ct.startsWith("video/") ||
            ct.startsWith("audio/") ||
            ct.startsWith("application/octet-stream") ||
            ct.startsWith("binary/octet-stream")
    }

    // -------------------------------------------------------------------------
    // URL builders (per dialect)
    // -------------------------------------------------------------------------

    private fun postUrl(base: String, board: Board, tags: String, page: Int, limit: Int): String =
        when (board.apiKind) {
            ApiKind.MOEBOORU -> "$base/post.json?tags=${enc(tags)}&limit=$limit&page=$page"
            ApiKind.GELBOORU ->
                "$base/index.php?page=dapi&s=post&q=index&json=1" +
                    "&tags=${enc(tags)}&limit=$limit&pid=${page - 1}"
            ApiKind.DANBOORU -> "$base/posts.json?tags=${enc(tags)}&limit=$limit&page=$page"
        }

    private fun postByIdUrl(base: String, board: Board, id: Int): String =
        when (board.apiKind) {
            ApiKind.MOEBOORU -> "$base/post.json?tags=id:$id&limit=1"
            ApiKind.GELBOORU -> "$base/index.php?page=dapi&s=post&q=index&json=1&id=$id"
            ApiKind.DANBOORU -> "$base/posts.json?tags=id:$id&limit=1"
        }

    private fun tagUrl(base: String, board: Board, prefix: String, limit: Int, exact: Boolean = false): String =
        when (board.apiKind) {
            ApiKind.MOEBOORU -> "$base/tag.json?name=${enc(prefix + if(exact) "" else "*")}&order=count&limit=$limit"
            ApiKind.GELBOORU ->
                "$base/index.php?page=dapi&s=tag&q=index&json=1" +
                    "&name_pattern=${enc(prefix + if(exact) "" else "%")}&limit=$limit"
            // brackets kept encoded: danbooru param names contain [ ]
            ApiKind.DANBOORU ->
                "$base/tags.json?search%5Bname_matches%5D=${enc(prefix + if(exact) "" else "*")}" +
                    "&search%5Border%5D=count&limit=$limit"
        }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Append the SFW/NSFW rating clause to a tag query. Skips when the caller
     * already wrote their own `rating:` term (they know what they want), and
     * when the board cannot express the mode (everything => no clause).
     */
    private fun applySfw(tags: String, apiKind: ApiKind, mode: SfwMode): String {
        val clause = ratingClause(apiKind, mode) ?: return tags
        if (clause.isBlank()) return tags
        if (tags.contains("rating:", ignoreCase = true)) return tags
        return listOf(tags, clause).filter { it.isNotBlank() }.joinToString(" ")
    }

    companion object {
        /**
         * Create a ready-to-use client. Pass a [BooruConfig] for tuning, and
         * optionally your own OkHttp clients so connection pooling / caches are
         * shared with the rest of the app.
         *
         * ```kotlin
         * val api = BooruApi.create()
         * ```
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            config: BooruConfig = BooruConfig(),
            jsonHttpClient: OkHttpClient? = null,
            mediaHttpClient: OkHttpClient? = null,
            rateLimiters: Map<Board, RateLimiter>? = null,
        ): BooruApi = BooruApi(BooruHttp(config, jsonHttpClient, mediaHttpClient, rateLimiters))

        private val DEFAULT_JSON =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }

        /** Rewrite a `*.gelbooru.com/<path>` URL to the moeview image/media edge. */
        private fun gelbooruEdgeUrl(edgeBase: String, url: String): String? {
            val match = GELBOORU_CDN.find(url) ?: return null
            return "$edgeBase/${match.groupValues[1]}"
        }

        private val GELBOORU_CDN = Regex("""^https?://(?:[^/]+\.)?gelbooru\.com/(.+)$""")
    }
}
