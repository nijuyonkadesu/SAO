package one.njk.sao.floating

import java.text.Normalizer
import java.util.Locale

data class MediaItem(
    val id: String,
    val title: String,
    val asset: String,
    val mimeType: String,
    val tags: List<String>,
    val emojis: List<String>,
    val animated: Boolean = false,
    val remote: RemoteMedia? = null,
) {
    val preview get() = "file:///android_asset/$asset"
    val isVideo get() = mimeType.startsWith("video/")
    val hasMotion get() = animated || isVideo
}

data class RemoteMedia(val sourceId: String, val assetId: String, val edited: Boolean)
data class MediaPage(val items: List<MediaItem>, val nextCursor: String? = null)

enum class SearchMode { CONTEXT, FILENAME, DESCRIPTION, PATH, OCR }
enum class SearchMedia { ALL, IMAGES, VIDEOS }
enum class PlaceLevel { COUNTRY, STATE, CITY }
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

enum class MessagingTarget(val packages: List<String>) {
    WHATSAPP(listOf("com.whatsapp")),
    WHATSAPP_BUSINESS(listOf("com.whatsapp.w4b")),
    TELEGRAM(listOf("org.telegram.messenger", "org.telegram.messenger.web", "nu.gpu.nagram", "nu.gpu.nagramx")),
    DISCORD(listOf("com.discord")),
    INSTAGRAM(listOf("com.instagram.android")),
    OTHER(emptyList());

    val isWhatsapp get() = this == WHATSAPP || this == WHATSAPP_BUSINESS

    companion object {
        fun fromPackage(packageName: String?) = entries.firstOrNull { packageName in it.packages } ?: OTHER
    }
}

enum class Rendition { MEDIA, STICKER_PNG, STICKER_WEBP }

data class DeliveryChoice(val rendition: Rendition, val mediaFallback: Boolean)

object DeliveryPolicy {
    // Discord's native composer receives image/* URIs, including GIFs, but not video/*.
    // Verified against the unmodified 348.10 composer; see docs/discord-apk-analysis.md.
    fun supportsPaste(item: MediaItem, target: MessagingTarget) = when (target) {
        MessagingTarget.DISCORD -> item.mimeType.startsWith("image/")
        // Instagram exposes keyboard commitContent, which does not establish clipboard
        // support. Its separate LSPosed Send photo capability is checked by the native bridge.
        MessagingTarget.INSTAGRAM -> false
        else -> true
    }
    fun clipboard(item: MediaItem, preferStickers: Boolean, target: MessagingTarget): DeliveryChoice {
        val rendition = when {
            !preferStickers || item.hasMotion -> Rendition.MEDIA
            target.isWhatsapp -> Rendition.STICKER_PNG
            target == MessagingTarget.TELEGRAM -> Rendition.STICKER_WEBP
            else -> Rendition.MEDIA
        }
        return DeliveryChoice(rendition, preferStickers && rendition == Rendition.MEDIA)
    }
}
