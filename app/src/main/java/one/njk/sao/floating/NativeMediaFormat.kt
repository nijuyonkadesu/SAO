package one.njk.sao.floating

enum class InstagramVideoRoute { VIDEO, GIF }

/** Prefer the host's original-media pipeline before considering a converted rendition. */
internal object NativeMediaFormat {
    fun mime(original: String,instagram: Boolean,accepts: (String)->Boolean,
        route: InstagramVideoRoute = InstagramVideoRoute.VIDEO): String {
        // An explicit GIF choice must not silently send the original video with audio.
        if(instagram && original.startsWith("video/") && route==InstagramVideoRoute.GIF) return "image/gif"
        if(accepts(original)) return original
        val compatible=VideoCompatibility.mime(original,accepts)
        if(compatible!=original && accepts(compatible)) return compatible
        if(instagram && original.startsWith("video/") && accepts("image/gif")) return "image/gif"
        return original
    }
}
