package one.njk.sao.floating

/** Account limits are unknown. This threshold prompts; it never silently changes media. */
object AttachmentSizePolicy {
    const val DISCORD_FREE_BYTES=20L*1024*1024
    fun warningLimit(target: MessagingTarget): Long? = when(target) {
        MessagingTarget.DISCORD -> DISCORD_FREE_BYTES
        else -> null
    }
    fun canReduce(mime: String, animated: Boolean) = mime.startsWith("video/") ||
        !animated && mime in setOf("image/jpeg","image/png")
}
