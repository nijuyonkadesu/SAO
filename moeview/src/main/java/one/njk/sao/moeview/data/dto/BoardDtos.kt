package one.njk.sao.moeview.data.dto

import one.njk.sao.moeview.data.ApiKind
import one.njk.sao.moeview.data.MediaType
import one.njk.sao.moeview.data.Post
import one.njk.sao.moeview.data.Rating
import one.njk.sao.moeview.data.Tag
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---------------------------------------------------------------------------
// moebooru dialect: Yande.re, Konachan (arrays of objects)
// ---------------------------------------------------------------------------

@Serializable
data class MoebooruPost(
    val id: Int = 0,
    val tags: String = "",
    val rating: String = "",
    val score: Int = 0,
    val md5: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("sample_url") val sampleUrl: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("jpeg_url") val jpegUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("file_ext") val fileExt: String? = null,
    val source: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
data class MoebooruTag(
    val id: Long = 0,
    val name: String = "",
    val count: Int = 0,
    val type: Int = 0,
)

// ---------------------------------------------------------------------------
// Gelbooru dialect (DAPI, envelope objects)
// ---------------------------------------------------------------------------

@Serializable
data class GelbooruResponse(
    @SerialName("post") val posts: List<GelbooruPost> = emptyList(),
)

@Serializable
data class GelbooruPost(
    val id: Int = 0,
    val rating: String = "",
    val tags: String = "",
    val score: Int = 0,
    val md5: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("sample_url") val sampleUrl: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val source: String? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    // Gelbooru's DAPI does not expose file_ext; media type is derived from file_url.
)

@Serializable
data class GelbooruTagResponse(
    @SerialName("tag") val tags: List<GelbooruTag> = emptyList(),
)

@Serializable
data class GelbooruTag(
    val id: Long = 0,
    val name: String = "",
    val count: Int = 0,
    val type: Int = 0,
)

// ---------------------------------------------------------------------------
// Danbooru dialect (arrays of objects)
// ---------------------------------------------------------------------------

@Serializable
data class DanbooruPost(
    val id: Int = 0,
    @SerialName("tag_string") val tags: String = "",
    val rating: String = "",
    val score: Int = 0,
    val md5: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("large_file_url") val largeFileUrl: String? = null,
    @SerialName("preview_file_url") val previewFileUrl: String? = null,
    val source: String? = null,
    @SerialName("image_width") val width: Int? = null,
    @SerialName("image_height") val height: Int? = null,
    @SerialName("file_ext") val fileExt: String? = null,
    // Danbooru returns ISO-8601 (e.g. "2024-01-01T00:00:00.000-05:00"), unlike moebooru/Gelbooru epoch seconds.
    @SerialName("created_at") val createdAt: String? = null,
    /** Video/animated media duration in seconds (null for still images). */
    val duration: Double? = null,
)

@Serializable
data class DanbooruTag(
    val id: Long = 0,
    val name: String = "",
    @SerialName("post_count") val count: Int = 0,
    @SerialName("category") val type: Int = 0,
)

// ---------------------------------------------------------------------------
// Mappers to unified models
// ---------------------------------------------------------------------------

fun MoebooruPost.toPost(): Post =
    Post(
        id = id,
        rating = Rating.from(rating, ApiKind.MOEBOORU),
        rawRating = rating,
        tags = tags.split(' ').filter { it.isNotBlank() },
        tagsRaw = tags,
        score = score,
        md5 = md5,
        fileUrl = fileUrl ?: jpegUrl,
        sampleUrl = sampleUrl,
        previewUrl = previewUrl,
        width = width,
        height = height,
        fileExt = fileExt,
        source = source,
        createdAt = createdAt,
        mediaType = MediaType.detect(fileExt, fileUrl, jpegUrl, sampleUrl, previewUrl),
    )

fun GelbooruPost.toPost(): Post =
    Post(
        id = id,
        rating = Rating.from(rating, ApiKind.GELBOORU),
        rawRating = rating,
        tags = tags.split(' ').filter { it.isNotBlank() },
        tagsRaw = tags,
        score = score,
        md5 = md5,
        fileUrl = fileUrl,
        sampleUrl = sampleUrl,
        previewUrl = previewUrl,
        width = width,
        height = height,
        source = source,
        createdAt = parseGelbooruTime(createdAt),
        mediaType = MediaType.detect(null, fileUrl, sampleUrl, previewUrl),
    )

fun DanbooruPost.toPost(): Post =
    Post(
        id = id,
        rating = Rating.from(rating, ApiKind.DANBOORU),
        rawRating = rating,
        tags = tags.split(' ').filter { it.isNotBlank() },
        tagsRaw = tags,
        score = score,
        md5 = md5,
        fileUrl = fileUrl,
        sampleUrl = largeFileUrl,
        previewUrl = previewFileUrl,
        width = width,
        height = height,
        fileExt = fileExt,
        source = source,
        createdAt = parseIso8601(createdAt),
        mediaType = MediaType.detect(fileExt, fileUrl, largeFileUrl, previewFileUrl),
        durationSeconds = duration,
    )

/**
 * Parse ISO-8601 with offset ("2024-01-01T00:00:00.000-05:00" / "...Z") to epoch
 * seconds using java.time, available on this module's supported Android versions.
 */
fun parseIso8601(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        java.time.OffsetDateTime.parse(value).toEpochSecond()
    } catch (e: Exception) {
        null
    }
}

/** Parse a raw response body into [Post]s according to the board's dialect. */
fun parsePosts(apiKind: ApiKind, body: String, json: Json): List<Post> =
    when (apiKind) {
        ApiKind.MOEBOORU -> json.decodeFromString<List<MoebooruPost>>(body).map { it.toPost() }
        ApiKind.GELBOORU -> json.decodeFromString<GelbooruResponse>(body).posts.map { it.toPost() }
        ApiKind.DANBOORU -> json.decodeFromString<List<DanbooruPost>>(body).map { it.toPost() }
    }

/** Parse a raw tag response into [Tag]s according to the board's dialect. */
fun parseTags(apiKind: ApiKind, body: String, json: Json): List<Tag> =
    when (apiKind) {
        ApiKind.MOEBOORU -> json.decodeFromString<List<MoebooruTag>>(body).map { Tag(it.id, it.name, it.count, it.type) }
        ApiKind.GELBOORU -> json.decodeFromString<GelbooruTagResponse>(body).tags.map { Tag(it.id, it.name, it.count, it.type) }
        ApiKind.DANBOORU -> json.decodeFromString<List<DanbooruTag>>(body).map { Tag(it.id, it.name, it.count, it.type) }
    }

private fun parseGelbooruTime(value: JsonElement?): Long? {
    val text=value?.jsonPrimitive?.contentOrNull ?: return null
    return text.toLongOrNull() ?: runCatching {
        SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(text)?.time?.div(1000)
    }.getOrNull()
}
