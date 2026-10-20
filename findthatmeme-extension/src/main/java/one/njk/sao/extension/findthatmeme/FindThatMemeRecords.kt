package one.njk.sao.extension.findthatmeme

import okhttp3.HttpUrl.Companion.toHttpUrl
import one.njk.sao.source.*
import org.json.JSONObject
import java.util.UUID

/** Domain mapping only. SAO chooses when to request the next page and owns all media bytes. */
internal class FindThatMemeRecords(private val store: FindThatMemeProfiles) {
    fun capabilities() = SourceCapabilities(
        modes = setOf(SearchMode.DESCRIPTION), facets = setOf(SearchFacet.MEDIA),
        defaultMode = SearchMode.DESCRIPTION,
        categories = listOf("This is fine" to "this is fine", "No idea" to "i have no idea what i am doing",
            "Distracted boyfriend" to "distracted boyfriend", "Please stop" to "please stop",
            "Trolley problem" to "trolley problem", "Cats" to "cat").mapIndexed { index, (label, text) ->
            SourceCategory("meme-$index", label, MediaQuery(text, mode = SearchMode.DESCRIPTION))
        },
    )

    suspend fun page(profile: FindThatMemeProfile, source: String, query: MediaQuery, cursor: String?, api: FindThatMemeApi): MediaPage {
        val normalized = capabilities().normalize(query)
        val fingerprint = digest(SourceWire.query(normalized).toString())
        val prior = cursor?.let { bound(profile, store.open(it)) }
        if (prior != null && prior.getString("query") != fingerprint) throw ExtensionFailure("INVALID_RESPONSE")
        val offset = prior?.getInt("offset") ?: 0
        if (offset !in 0..(Int.MAX_VALUE - FindThatMemeApi.PAGE_SIZE)) throw ExtensionFailure("INVALID_RESPONSE")
        val type = when (normalized.constraints.media) {
            SearchMedia.ALL -> ""; SearchMedia.IMAGES -> "type:image"; SearchMedia.VIDEOS -> "type:video"
        }
        val terms = listOf(profile.config.terms, normalized.text, type).filter(String::isNotBlank).joinToString(" ")
        if (terms.length > 2048) throw ExtensionFailure("LIMIT_EXCEEDED")
        val rows = api.search(terms, offset)
        val ids = List(rows.length()) { rows.getJSONObject(it).optString("id") }
        val pageHash = digest(ids.joinToString("|"))
        if (rows.length() > 0 && prior?.optString("previous") == pageHash) throw ExtensionFailure("INVALID_RESPONSE")
        val items = List(rows.length()) { record(profile, source, rows.getJSONObject(it), normalized.constraints.media) }
            .filterNotNull().distinctBy { it.remote!!.assetId }
        val next = if (rows.length() == FindThatMemeApi.PAGE_SIZE) store.seal(scope(profile)
            .put("query", fingerprint).put("offset", offset + rows.length()).put("previous", pageHash)) else null
        return MediaPage(items, next)
    }

    private fun record(profile: FindThatMemeProfile, source: String, row: JSONObject, media: SearchMedia): MediaItem? {
        val id = row.optString("id")
        if (runCatching { UUID.fromString(id) }.isFailure || (!profile.config.sensitive && sensitive(row))) return null
        val file = FindThatMemeApi.mediaUrl(row.optString("image_url").ifBlank { row.optString("image_path") }) ?: return null
        val mime = FindThatMemeApi.mime(file) ?: return null
        val video = mime.startsWith("video/")
        if ((media == SearchMedia.IMAGES && video) || (media == SearchMedia.VIDEOS && !video)) return null
        val poster = FindThatMemeApi.mediaUrl(row.optString("thumbnail_url").ifBlank { row.optString("thumbnail") }, thumbnail = true)
            ?.takeIf { FindThatMemeApi.mime(it)?.startsWith("image/") == true }
        if (video && poster == null) return null
        // Avoid small grid thumbnails for pictures, and retain the full GIF for animation.
        val preview = if (video) requireNotNull(poster) else file
        val token = store.seal(scope(profile).put("id", id).put("file", file).put("preview", preview).put("mime", mime))
        val originalKey = digest(file)
        val previewKey = if (preview == file) originalKey else digest(preview)
        val short = row.optString("short_id").take(32).ifBlank { id.take(8) }
        val site = row.optString("source_site").take(40).ifBlank { "FindThatMeme" }
        return MediaItem("findthatmeme_" + digest("$source:$id"), "$site · $short", token, mime,
            emptyList(), emptyList(), mime in setOf("image/gif", "image/webp"),
            RemoteMedia(source, id, false, cacheKeys = mapOf("original" to originalKey, "video" to originalKey,
                "preview" to previewKey, "raster" to if (video) previewKey else originalKey)),
            file.toHttpUrl().pathSegments.last().take(256))
    }

    fun reference(profile: FindThatMemeProfile, token: String): JSONObject = bound(profile, store.open(token)).also {
        if (!FindThatMemeApi.allowedMedia(it.getString("file")) || !FindThatMemeApi.allowedMedia(it.getString("preview")) ||
            FindThatMemeApi.mime(it.getString("file")) != it.getString("mime") ||
            runCatching { UUID.fromString(it.getString("id")) }.isFailure) throw ExtensionFailure("INVALID_RESPONSE")
    }

    fun variant(ref: JSONObject, kind: String): Pair<String, String> {
        val video = ref.getString("mime").startsWith("video/")
        val url = when (kind) {
            "original" -> ref.getString("file")
            "video" -> if (video) ref.getString("file") else throw ExtensionFailure("UNSUPPORTED")
            "preview", "raster" -> ref.getString(if (video) "preview" else "file")
            else -> throw ExtensionFailure("UNSUPPORTED")
        }
        return url to (FindThatMemeApi.mime(url) ?: throw ExtensionFailure("INVALID_RESPONSE"))
    }

    private fun sensitive(row: JSONObject): Boolean {
        val porn = row.optDouble("nsfw_porn", -1.0)
        val sexy = row.optDouble("nsfw_sexy", -1.0)
        return porn >= 50 || row.optDouble("nsfw_hentai", -1.0) >= 50 || sexy >= 90 || (sexy >= 60 && porn >= 20)
    }
    private fun scope(profile: FindThatMemeProfile) = JSONObject().put("profile", profile.id).put("revision", profile.revision)
    private fun bound(profile: FindThatMemeProfile, value: JSONObject): JSONObject = value.also {
        if (it.optString("profile") != profile.id || it.optString("revision") != profile.revision) throw ExtensionFailure("PROFILE_CHANGED")
    }
}
