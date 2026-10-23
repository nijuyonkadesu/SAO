package one.njk.sao.source

import java.text.Normalizer
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale

const val LOCAL_SOURCE_ID = "local"

data class MediaItem(
    val id: String,
    val title: String,
    val asset: String,
    val mimeType: String,
    val tags: List<String>,
    val emojis: List<String>,
    val animated: Boolean = false,
    val remote: RemoteMedia? = null,
    /** Original filename, independent of a user-edited title or description. */
    val filename: String? = null,
) {
    val isVideo get() = mimeType.startsWith("video/")
    val hasMotion get() = animated || isVideo
}

data class RemoteMedia(val sourceId: String, val assetId: String, val edited: Boolean, val uploadedAt: String? = null,
    /** Opaque per-asset cache identities; variants with identical bytes share a key. */
    val cacheKeys: Map<String, String> = emptyMap())
data class MediaPage(val items: List<MediaItem>, val nextCursor: String? = null,
    /** Successful items are usable; continuation requires an explicit retry. */
    val interrupted: Boolean = false)

/** An asset's stable owner and backend identity, independent of rendition/content revisions. */
data class MediaIdentity(val owner: String, val asset: String) {
    /** Canonical, delimiter-safe saved-state representation; provider IDs remain opaque. */
    fun encode(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString(owner.toByteArray(Charsets.UTF_8)) + "." +
            encoder.encodeToString(asset.toByteArray(Charsets.UTF_8))
    }

    companion object {
        /** Malformed or oversized restored focus is ignored instead of breaking browsing. */
        fun decode(value: String?): MediaIdentity? {
            if (value == null || value.length > 16 * 1024) return null
            return runCatching {
                val fields = value.split('.')
                require(fields.size == 2)
                val decoder = Base64.getUrlDecoder()
                fun text(field: String) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decoder.decode(field))).toString()
                MediaIdentity(text(fields[0]), text(fields[1])).also { require(it.encode() == value) }
            }.getOrNull()
        }
    }
}

/** Stable across asset revisions and distinct across accounts/providers. */
val MediaItem.identityKey get() = remote?.let { MediaIdentity(it.sourceId, it.assetId) } ?: MediaIdentity(LOCAL_SOURCE_ID, id)

enum class SearchMode { CONTEXT, FILENAME, DESCRIPTION, PATH, OCR }
enum class SearchMedia { ALL, IMAGES, VIDEOS }
enum class PlaceLevel { COUNTRY, STATE, CITY }

data class PlaceScope(val level: PlaceLevel, val country: String? = null, val state: String? = null) {
    init {
        require(level != PlaceLevel.COUNTRY || country == null)
        require(level != PlaceLevel.STATE || state == null)
    }
}
data class SearchPerson(val id: String, val name: String, val sourceId: String? = null)
data class PeoplePage(val people: List<SearchPerson>, val nextPage: Int? = null)
data class SearchConstraints(val sourceId: String? = null, val people: List<SearchPerson> = emptyList(),
    val country: String? = null, val state: String? = null, val city: String? = null,
    val media: SearchMedia = SearchMedia.ALL) {
    val count get() = (if (people.isEmpty()) 0 else 1) +
        (if (country == null && state == null && city == null) 0 else 1) + (if (media == SearchMedia.ALL) 0 else 1)
    fun forSource(id: String) = if (sourceId == null || sourceId == id) this else SearchConstraints(media = media)
}
data class MediaQuery(val text: String = "", val filter: String? = null,
    val mode: SearchMode = SearchMode.CONTEXT, val constraints: SearchConstraints = SearchConstraints(),
    // Identifies a user-requested batch, not a seed sent to the server. Reopening retains that batch.
    val randomRequest: String? = null)

/** Matching never splits an emoji into UTF-16 code units or removes ZWJ/skin tones. */
object MediaSearch {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace("\uFE0F", "").replace("\uFE0E", "")

    fun search(items: List<MediaItem>, query: MediaQuery): List<MediaItem> {
        val terms = normalize(query.text).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return items.filter { item ->
            val fields = listOf(item.title) + item.tags + item.emojis
            val searchable = fields.map(::normalize)
            (query.filter == null || fields.any { normalize(it) == normalize(query.filter) }) &&
                terms.all { term -> searchable.any { term in it } }
        }
    }
}
