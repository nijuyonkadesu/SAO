package one.njk.sao.floating

/** Prefer the host's original-media pipeline before considering a converted rendition. */
internal object NativeMediaFormat {
    fun mime(original: String,instagram: Boolean,accepts: (String)->Boolean): String {
        if(accepts(original)) return original
        val compatible=VideoCompatibility.mime(original,accepts)
        if(compatible!=original && accepts(compatible)) return compatible
        if(instagram && original.startsWith("video/") && accepts("image/gif")) return "image/gif"
        return original
    }
}
