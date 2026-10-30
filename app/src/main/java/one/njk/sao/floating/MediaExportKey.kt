package one.njk.sao.floating

import one.njk.sao.source.LOCAL_SOURCE_ID
import one.njk.sao.source.MediaItem
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Export policy is part of identity: differently constrained copies must never alias. */
internal sealed interface MediaExportPolicy {
    data object Displayed : MediaExportPolicy
    data class Prepared(val rendition: ExportRendition, val sticker: StickerRequirements) : MediaExportPolicy
    data object CompatibleMp4 : MediaExportPolicy
    data object AsGif : MediaExportPolicy
    data class DecodeInput(val playback: Boolean) : MediaExportPolicy
    data class Limited(val mime: String, val maxBytes: Long) : MediaExportPolicy
}

/** IDs are source-scoped. Cache revisions describe bytes independently of display metadata. */
internal data class MediaExportKey(val item: MediaItem, val policy: MediaExportPolicy) {
    fun filename(extension: String): String {
        require(extension.matches(Regex("[a-zA-Z0-9]+")))
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                fun field(value: String) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    data.writeInt(encoded.size); data.write(encoded)
                }
                field("sao-export-v1")
                field(item.remote?.sourceId ?: LOCAL_SOURCE_ID)
                field(item.remote?.assetId ?: item.asset)
                field(item.id); field(item.mimeType)
                data.writeBoolean(item.animated); data.writeBoolean(item.remote?.edited == true)
                val revisions = item.remote?.cacheKeys.orEmpty().toSortedMap()
                data.writeInt(revisions.size)
                revisions.forEach { (kind, revision) -> field(kind); field(revision) }
                when (val operation = policy) {
                    MediaExportPolicy.Displayed -> field("displayed")
                    is MediaExportPolicy.Prepared -> {
                        field("prepared"); field(operation.rendition.name)
                        data.writeInt(operation.sticker.maxBytes)
                    }
                    MediaExportPolicy.CompatibleMp4 -> field("compatible-mp4")
                    MediaExportPolicy.AsGif -> field("instagram-gif-gifski-1.34-v1")
                    is MediaExportPolicy.DecodeInput -> { field("decode-input-v1"); data.writeBoolean(operation.playback) }
                    is MediaExportPolicy.Limited -> {
                        field("limited"); field(operation.mime); data.writeLong(operation.maxBytes)
                    }
                }
            }
            output.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$digest.$extension"
    }
}
