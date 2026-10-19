package one.njk.sao.moeview.data

/**
 * One post, normalized across all four boards.
 *
 * The raw response is kept alongside derived fields so nothing is lost:
 * [tags] is the parsed tag list, [tagsRaw] the space-separated source string.
 *
 * Media URLs are the *direct* CDN URLs from the board API and may need a
 * `Referer` (see [Board.mediaReferer]). Prefer [BooruApi.mediaUrl] /
 * [BooruApi.mediaRequest] / [BooruApi.openMedia], which pick the right
 * transport (edge vs direct) and supply the headers.
 */
data class Post(
    val id: Int,
    val rating: Rating,
    val rawRating: String,
    val tags: List<String>,
    val tagsRaw: String,
    val score: Int,
    val md5: String? = null,
    val fileUrl: String? = null,
    val sampleUrl: String? = null,
    val previewUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fileExt: String? = null,
    val source: String? = null,
    val createdAt: Long? = null,
    /** Detected media kind (image / gif / video). */
    val mediaType: MediaType = MediaType.IMAGE,
    /** Video duration in seconds when the board reports it (Danbooru only). */
    val durationSeconds: Double? = null,
) {
    val isNsfw: Boolean
        get() = !rating.isSfw

    val isVideo: Boolean
        get() = mediaType == MediaType.VIDEO
}
