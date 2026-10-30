package one.njk.sao.source

import android.net.Uri

/** A scoped, credential-free playback address. Providers retain transport/authentication ownership. */
data class MediaPlayback(val uri: Uri, val cacheKey: String? = null) {
    init { require(uri.scheme in setOf("content", "file")) }
}

/** Optional ContentProvider transport options; older providers retain sequential playback. */
object MediaStreamOptions {
    const val STREAMING = "one.njk.sao.media.STREAMING"
    const val POSITION = "one.njk.sao.media.POSITION"
    const val CONTROL = "one.njk.sao.media.CONTROL"
}
