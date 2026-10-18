package one.njk.sao.extension.local

import one.njk.sao.source.MediaSearch
import java.util.Locale

data class Folder(val uri: String, val name: String)
data class Library(val id: String, val name: String, val revision: String, val folders: List<Folder>,
    val paused: Boolean = false, val lastScan: Long = 0)
data class LocalAsset(val id: Long, val profile: String, val root: String, val uri: String,
    val filename: String, val path: String, val mime: String, val modified: Long, val size: Long,
    val signature: String, val animated: Boolean = false, val labels: String = "", val ocr: String = "",
    val title: String? = null, val description: String? = null, val tags: String? = null,
    val pending: Boolean = true, val failure: String? = null, val ocrOverride: String? = null) {
    val displayTitle get() = title?.takeIf(String::isNotBlank) ?: filename
    val effectiveTags get() = (tags ?: labels).split('\n').filter(String::isNotBlank).distinct()
    val effectiveOcr get() = ocrOverride ?: ocr
}
data class IndexStatus(val total: Int, val pending: Int, val failed: Int, val scanning: Boolean, val folderErrors: Int)
data class Analysis(val labels: List<String>, val text: String)

object LocalFormats {
    private val extensions = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "webp" to "image/webp", "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska")
    fun mime(name: String, reported: String?): String? = extensions[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
        ?: reported?.takeIf { it in extensions.values }
    fun terms(value: String) = MediaSearch.normalize(value).trim().split(Regex("\\s+")).filter(String::isNotBlank).take(24)
    fun like(value: String) = "%" + MediaSearch.normalize(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    fun tags(value: String) = value.split(',', '\n').map(String::trim).filter(String::isNotBlank).distinct().take(32).joinToString("\n") { it.take(80) }
    private val emojiLabels=mapOf("smile" to "😊", "cat" to "🐱", "dog" to "🐶", "bird" to "🐦", "flower" to "🌸",
        "love" to "❤️", "heart" to "❤️", "sunset" to "🌅", "beach" to "🏖️", "food" to "🍽️", "coffee" to "☕")
    fun emojis(labels: List<String>)=labels.mapNotNull { emojiLabels[it.lowercase(Locale.ROOT)] }.distinct()
}
