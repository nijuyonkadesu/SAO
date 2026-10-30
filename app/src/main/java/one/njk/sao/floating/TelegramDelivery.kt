package one.njk.sao.floating

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent

/** Telegram's document route preserves .webp and adds its native sticker attributes.
 * An image MIME share instead enters its photo editor and may be recompressed to JPEG.
 * See docs/telegram-delivery.md for the pinned Telegram/Nagram X source checks.
 */
object TelegramDelivery {
    const val DOCUMENT_MIME = "application/octet-stream"
    fun stickerIntent(media: ExportedMedia, targetPackage: String, title: String): Intent {
        require(PackageNames.valid(targetPackage))
        require(media.mimeType == "image/webp" && media.file.extension == "webp")
        return Intent(Intent.ACTION_SEND).setPackage(targetPackage).setType(DOCUMENT_MIME)
            .putExtra(Intent.EXTRA_STREAM, media.uri)
            .apply {
                // The URI and ClipData retain the actual MIME; the outer type selects document handling.
                clipData = ClipData(ClipDescription(title, arrayOf(media.mimeType)), ClipData.Item(media.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}
