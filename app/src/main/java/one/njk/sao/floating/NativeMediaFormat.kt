package one.njk.sao.floating

enum class MotionDelivery { ORIGINAL, GIF }

/** Prefer the host's original-media pipeline before considering a converted rendition. */
internal object NativeMediaFormat {
    fun mime(original: String,accepts: (String)->Boolean,
        route: MotionDelivery = MotionDelivery.ORIGINAL, motion: Boolean = original.startsWith("video/")): String {
        // An explicit GIF choice must not silently send the original video with audio.
        if(motion && route==MotionDelivery.GIF) return "image/gif"
        if(accepts(original)) return original
        val compatible=VideoCompatibility.mime(original,accepts)
        if(compatible!=original && accepts(compatible)) return compatible
        return original
    }
}
