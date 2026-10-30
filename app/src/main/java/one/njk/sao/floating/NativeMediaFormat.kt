package one.njk.sao.floating

enum class MotionDelivery { ORIGINAL, GIF }

/** Prefer the host's original-media pipeline before considering a converted rendition. */
internal object NativeMediaFormat {
    fun mime(original: String,accepts: (String)->Boolean,
        route: MotionDelivery = MotionDelivery.ORIGINAL, motion: Boolean = original.startsWith("video/"),
        target: MessagingTarget = MessagingTarget.OTHER): String {
        val desired=DeliveryPolicy.mediaMime(original,target,motion,route)
        if(accepts(desired)) return desired
        val compatible=VideoCompatibility.mime(desired,accepts)
        if(compatible!=desired && accepts(compatible)) return compatible
        return desired
    }
}
