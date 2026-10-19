package one.njk.sao.moeview

import one.njk.sao.moeview.data.SfwMode

/**
 * Everything tunable about the moeview/booru network layer.
 *
 * Defaults are deliberately conservative: the moeview edges are shared,
 * undocumented infra, so we pace requests per host and never hammer them.
 */
data class BooruConfig(
    /** User-Agent sent with every request. Some boards reject default/empty UAs. */
    val userAgent: String = "BooruBrowser/1.0 (Android)",

    /** Connect timeout in ms. */
    val connectTimeoutMs: Long = 10_000,

    /** Read timeout in ms for JSON/API calls (large JSON responses on slow links). */
    val readTimeoutMs: Long = 30_000,

    /**
     * Read timeout in ms for media streams (images/video). `0` = no timeout,
     * which is what you want for long videos that pause mid-stream.
     */
    val mediaReadTimeoutMs: Long = 0,

    /** Write timeout in ms. */
    val writeTimeoutMs: Long = 15_000,

    /** Minimum interval between requests to the same board (rate limiting). */
    val minRequestIntervalMs: Long = 400,

    /** Retries per transport for transient failures (I/O errors, 429, 5xx). */
    val maxRetriesPerTransport: Int = 2,

    /** First retry backoff; doubles each attempt (plus jitter). */
    val retryBaseDelayMs: Long = 600,

    /** Backoff ceiling. */
    val retryMaxDelayMs: Long = 4_000,

    /** OkHttp global max in-flight requests. */
    val maxRequests: Int = 4,

    /** OkHttp max in-flight requests per host. */
    val maxRequestsPerHost: Int = 2,

    /** Enable moeview.app edge transports (set false to go board-direct only). */
    val useEdgeTransport: Boolean = true,

    /**
     * When the preferred transport fails, whether to try the other one.
     *
     * Set false to guarantee only "unfiltered" edge results for Konachan /
     * Gelbooru: a request then fails loudly instead of quietly falling back to
     * a direct API that masks categories.
     */
    val allowTransportFallback: Boolean = true,

    /**
     * Content segregation applied to every post query unless overridden per
     * call. Defaults to [SfwMode.SFW] so callers opt *in* to explicit content
     * (SFW/NSFW, mirroring waifu.pics).
     */
    val defaultSfwMode: SfwMode = SfwMode.SFW,

    /**
     * Tag names filtered out of [BooruApi.tags] results by default. Boorus do
     * not rate tags, so if you want tag-level segregation supply a curated list
     * here (e.g. `loli`, `guro`, ...). Empty by default.
     */
    val nsfwTagBlocklist: Set<String> = emptySet(),

    /** Extra headers added to every request (e.g. cookies). */
    val extraHeaders: Map<String, String> = emptyMap(),
)