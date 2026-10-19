package one.njk.sao.moeview.data

/**
 * Unified content rating.
 *
 * The boards use different vocabularies, and the *same letter means different
 * things* on different boards (moebooru `s` = **safe**, Danbooru `s` =
 * **sensitive**), so [from] is board-aware.
 *
 * | value | moebooru (Yande/Konachan) | Gelbooru | Danbooru |
 * |---|---|---|---|
 * | GENERAL | `g` / `general` | `general` | `g` |
 * | SAFE | `s` / `safe` | `safe` | — |
 * | SENSITIVE | — | `sensitive` | `s` |
 * | QUESTIONABLE | `q` | `questionable` | `q` |
 * | EXPLICIT | `e` | `explicit` | `e` |
 */
enum class Rating {
    GENERAL,
    SAFE,
    SENSITIVE,
    QUESTIONABLE,
    EXPLICIT,
    UNKNOWN;

    /** True for the ratings this library treats as "safe" by default. */
    val isSfw: Boolean
        get() = this == GENERAL || this == SAFE

    companion object {
        fun from(raw: String?, apiKind: ApiKind? = null): Rating {
            val v = raw?.trim()?.lowercase()
            if (v.isNullOrEmpty()) return UNKNOWN
            return when (apiKind) {
                ApiKind.MOEBOORU ->
                    when (v) {
                        "s", "safe" -> SAFE
                        "g", "general" -> GENERAL
                        "q", "questionable" -> QUESTIONABLE
                        "e", "explicit" -> EXPLICIT
                        else -> UNKNOWN
                    }
                ApiKind.DANBOORU ->
                    when (v) {
                        "g", "general" -> GENERAL
                        "s", "sensitive" -> SENSITIVE
                        "q", "questionable" -> QUESTIONABLE
                        "e", "explicit" -> EXPLICIT
                        else -> UNKNOWN
                    }
                ApiKind.GELBOORU ->
                    when (v) {
                        "g", "general" -> GENERAL
                        "s", "safe" -> SAFE
                        "sensitive" -> SENSITIVE
                        "q", "questionable" -> QUESTIONABLE
                        "e", "explicit" -> EXPLICIT
                        else -> UNKNOWN
                    }
                null ->
                    when (v) {
                        "g", "general" -> GENERAL
                        "s", "safe" -> SAFE
                        "sensitive" -> SENSITIVE
                        "q", "questionable" -> QUESTIONABLE
                        "e", "explicit" -> EXPLICIT
                        else -> UNKNOWN
                    }
            }
        }
    }
}

/**
 * Binary content segregation, mirroring waifu.pics' SFW/NSFW split. Applied to
 * every post query so callers opt *in* to explicit content.
 *
 * - [SFW] — only safe/general posts (default).
 * - [NSFW] — excludes safe/general posts (questionable + explicit + sensitive).
 * - [ALL] — no rating filter.
 */
enum class SfwMode {
    SFW,
    NSFW,
    ALL,
}

/** The tag token the board considers "safe" for a positive/negative rating filter. */
internal fun safeRatingToken(apiKind: ApiKind): String =
    when (apiKind) {
        ApiKind.MOEBOORU -> "s"
        ApiKind.GELBOORU -> "general"
        ApiKind.DANBOORU -> "g"
    }

/**
 * Build the rating filter clause for a board, or `null` for [SfwMode.ALL].
 *
 * The boards only support a *single* rating term (`rating:x` / `-rating:x`);
 * comma unions are unreliable (`rating:q,e` matches only `q` on moebooru), so
 * "NSFW" is expressed as the negation of the safe rating, which the boards do
 * support (`-rating:s`, `-rating:general`).
 */
fun ratingClause(apiKind: ApiKind, mode: SfwMode): String? =
    when (mode) {
        SfwMode.ALL -> null
        SfwMode.SFW -> "rating:${safeRatingToken(apiKind)}"
        SfwMode.NSFW -> "-rating:${safeRatingToken(apiKind)}"
    }
