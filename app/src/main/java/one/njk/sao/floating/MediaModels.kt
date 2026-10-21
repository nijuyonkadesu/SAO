package one.njk.sao.floating

import one.njk.sao.source.*

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
    fun stickerRequirements(target: MessagingTarget) =
        if(target==MessagingTarget.TELEGRAM) StickerRequirements.TELEGRAM else StickerRequirements.WHATSAPP
    // Discord's native composer receives image/* URIs, including GIFs, but not video/*.
    // Verified against the unmodified 348.10 composer; see docs/discord-apk-analysis.md.
    fun supportsPaste(item: MediaItem, target: MessagingTarget) = when (target) {
        MessagingTarget.DISCORD -> item.mimeType.startsWith("image/")
        // Instagram exposes keyboard commitContent, which does not establish clipboard
        // support. Its LSPosed photo Paste capability is checked by the native bridge.
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
