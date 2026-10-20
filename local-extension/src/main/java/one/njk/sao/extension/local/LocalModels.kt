package one.njk.sao.extension.local

import one.njk.sao.source.MediaSearch
import java.util.Locale

enum class CollectionKind { TREE, SELECTION }
data class Folder(val uri: String, val name: String, val paused: Boolean=false, val lastScan: Long=0,
    val kind: CollectionKind=CollectionKind.TREE) {
    val documentId get() = if(kind==CollectionKind.TREE) android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uri)) else "selected"
}
data class Library(val id: String, val name: String, val revision: String, val folders: List<Folder>,
    val paused: Boolean = false, val lastScan: Long = 0, val contextSearch: Boolean=false, val contextModel: String=ClipModels.MODEL)
data class LocalAsset(val id: Long, val profile: String, val root: String, val uri: String,
    val filename: String, val path: String, val mime: String, val modified: Long, val size: Long,
    val signature: String, val animated: Boolean = false, val labels: String = "", val ocr: String = "",
    val title: String? = null, val description: String? = null, val tags: String? = null,
    val pending: Boolean = true, val failure: String? = null, val ocrOverride: String? = null) {
    val displayTitle get() = title?.takeIf(String::isNotBlank) ?: filename
    val effectiveTags get() = (tags ?: labels).split('\n').filter(String::isNotBlank).distinct()
    val effectiveOcr get() = ocrOverride ?: ocr
}
data class IndexStatus(val total: Int, val pending: Int, val failed: Int, val scanning: Boolean, val folderErrors: Int,
    val images: Int=0, val animated: Int=0, val videos: Int=0, val scanned: Boolean=false,
    val permissionRequired: Boolean=false, val paused: Boolean=false, val contextSearch: Boolean=false,
    val contextProcessed: Int=0, val contextFailed: Int=0) {
    /** Successfully analyzed files; failures and remaining files are separate counts. */
    val processed get() = (total-pending-failed).coerceAtLeast(0)
    val contextPending get() = (total-contextProcessed-contextFailed).coerceAtLeast(0)
}
data class IndexOverview(val library: Library, val total: IndexStatus, val folders: Map<String,IndexStatus>)
enum class IndexWork { IDLE, WAITING, RUNNING }
data class Analysis(val labels: List<String>, val text: String)

object LocalFormats {
    private val extensions = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "webp" to "image/webp", "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska")
    val mimeTypes get()=extensions.values.toSet()
    fun importFilename(name: String,mime: String): String {
        if(extensions.containsKey(name.substringAfterLast('.',"").lowercase(Locale.ROOT))) return name
        val extension=extensions.entries.firstOrNull { it.value==mime }?.key ?: throw one.njk.sao.source.ExtensionFailure("UNSUPPORTED_MEDIA")
        return "${name.take(240)}.$extension"
    }
    fun mime(name: String, reported: String?): String? = extensions[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
        ?: reported?.takeIf { it in extensions.values }
    fun terms(value: String) = MediaSearch.normalize(value).trim().split(Regex("\\s+")).filter(String::isNotBlank).take(24)
    fun like(value: String) = "%" + MediaSearch.normalize(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    fun tags(value: String) = value.split(',', '\n').map(String::trim).filter(String::isNotBlank).distinct().take(32).joinToString("\n") { it.take(80) }
    private val emojiLabels=mapOf("smile" to "😊", "cat" to "🐱", "dog" to "🐶", "bird" to "🐦", "flower" to "🌸",
        "love" to "❤️", "heart" to "❤️", "sunset" to "🌅", "beach" to "🏖️", "food" to "🍽️", "coffee" to "☕")
    fun emojis(labels: List<String>)=labels.mapNotNull { emojiLabels[it.lowercase(Locale.ROOT)] }.distinct()
}
