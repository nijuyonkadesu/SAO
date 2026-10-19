package one.njk.sao.moeview.data

/** Which of the four booru API dialects is behind a board. */
enum class ApiKind {
    /** Yande.re / Konachan: JSON array responses, `/post.json`, `/tag.json`. */
    MOEBOORU,

    /** Gelbooru DAPI: `{"post":[..]}` envelope, `/index.php?page=dapi...`. */
    GELBOORU,

    /** Danbooru: JSON array responses, `/posts.json`, `/tags.json`. */
    DANBOORU,
}

/** Which HTTP transport a request is executed over. */
enum class Transport {
    /** The board's own origin (API, CDN). */
    DIRECT,

    /** moeview.app's reverse-proxy edge for a board. */
    EDGE,
}

/**
 * Order in which transports are attempted for a board's *API* calls, and
 * whether a fallback transport exists at all.
 *
 * Rationale: moeview's edges return *unfiltered* results for Konachan and
 * Gelbooru (direct Gelbooru DAPI masks certain categories with HTTP 401/403
 * from many IPs). Yande.re and Danbooru have no moeview edge, so they are
 * direct-only.
 */
enum class TransportStrategy {
    DIRECT_ONLY,
    DIRECT_FIRST,
    EDGE_FIRST,
    EDGE_ONLY;

    /** Ordered candidates, best first. */
    val candidates: List<Transport>
        get() =
            when (this) {
                DIRECT_ONLY -> listOf(Transport.DIRECT)
                DIRECT_FIRST -> listOf(Transport.DIRECT, Transport.EDGE)
                EDGE_FIRST -> listOf(Transport.EDGE, Transport.DIRECT)
                EDGE_ONLY -> listOf(Transport.EDGE)
            }
}

/**
 * The four boards served by moeview.app.
 *
 * @property directBase board API origin.
 * @property edgeBase moeview.app edge origin for the board's API (`null` = no edge).
 * @property mediaEdgeBase moeview.app edge origin for the board's media CDN (`null` = none).
 *   Note: the edge proxies bytes but does **not** honor HTTP Range, so it is fine
 *   for images/GIFs but not for seekable video.
 * @property mediaReferer `Referer` required to bypass hotlink protection on the
 *   board's direct CDN (`null` = not needed). Gelbooru's CDN 302-redirects to
 *   `hotlink.php` without it.
 * @property edgeIsSfwOnly true when the moeview edge for this board only serves
 *   safe posts (verified: the Konachan edge returns `s` for everything —
 *   `rating:q`, `rating:e` and `-rating:s` all come back empty). NSFW/ALL
 *   queries therefore skip the edge and go direct.
 */
enum class Board(
    val id: String,
    val label: String,
    val directBase: String,
    val edgeBase: String?,
    val mediaEdgeBase: String?,
    val mediaReferer: String?,
    val apiKind: ApiKind,
    val strategy: TransportStrategy,
    val edgeIsSfwOnly: Boolean,
    val maxPageSize: Int,
) {
    YANDERE(
        id = "yandere",
        label = "Yande.re",
        directBase = "https://yande.re",
        edgeBase = null,
        mediaEdgeBase = null,
        mediaReferer = null,
        apiKind = ApiKind.MOEBOORU,
        strategy = TransportStrategy.DIRECT_ONLY,
        edgeIsSfwOnly = false,
        maxPageSize = 100,
    ),
    KONACHAN(
        id = "konachan",
        label = "Konachan",
        directBase = "https://konachan.com",
        edgeBase = "https://moeview.app/edge/konachan",
        mediaEdgeBase = null,
        mediaReferer = null,
        apiKind = ApiKind.MOEBOORU,
        strategy = TransportStrategy.EDGE_FIRST,
        // Verified: the moeview Konachan edge serves only `s` (safe) posts.
        edgeIsSfwOnly = true,
        maxPageSize = 100,
    ),
    GELBOORU(
        id = "gelbooru",
        label = "Gelbooru",
        directBase = "https://gelbooru.com",
        edgeBase = "https://moeview.app/edge/gelbooru",
        mediaEdgeBase = "https://moeview.app/edge/gelbooru-img",
        // Hotlink protection: direct media 302-redirects unless this Referer is sent.
        mediaReferer = "https://gelbooru.com/",
        apiKind = ApiKind.GELBOORU,
        strategy = TransportStrategy.EDGE_FIRST,
        // Edge is unfiltered: general/sensitive/questionable/explicit all present.
        edgeIsSfwOnly = false,
        maxPageSize = 100,
    ),
    DANBOORU(
        id = "danbooru",
        label = "Danbooru",
        directBase = "https://danbooru.donmai.us",
        mediaEdgeBase = null,
        edgeBase = null,
        mediaReferer = null,
        apiKind = ApiKind.DANBOORU,
        strategy = TransportStrategy.DIRECT_ONLY,
        edgeIsSfwOnly = false,
        maxPageSize = 200,
    );

    /**
     * The transports this board can actually use for *API* calls, honoring the
     * configured strategy and the fact that there is no edge for some boards.
     */
    fun usableTransports(): List<Transport> =
        strategy.candidates.filter { it == Transport.DIRECT || edgeBase != null }
            .ifEmpty { listOf(Transport.DIRECT) }

    /** True when this board has a moeview media edge (bytes proxied, no Range). */
    val hasMediaEdge: Boolean
        get() = mediaEdgeBase != null

    companion object {
        val byId: Map<String, Board> = entries.associateBy { it.id }
    }
}
