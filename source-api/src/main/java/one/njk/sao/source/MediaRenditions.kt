package one.njk.sao.source

import android.graphics.Bitmap
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

enum class Rendition {
    ORIGINAL,
    PLAYBACK,
    STILL,
}

sealed interface MediaHandle {
    fun openStream(): InputStream

    val file: File?

    data class Stream(val stream: InputStream) : MediaHandle {
        override fun openStream() = stream
        override val file: File? get() = null
    }

    data class Local(val local: File) : MediaHandle {
        override fun openStream() = local.inputStream()
        override val file: File? get() = local
    }
}

fun MediaHandle.requireFile(): File = file
    ?: throw SourceException(message = "The source served this rendition without a local copy")

sealed interface Preview {
    data class Local(val file: File) : Preview
    data class Uri(val uri: android.net.Uri) : Preview
    data class Bytes(val buffer: ByteBuffer) : Preview
    data class Decoded(val bitmap: Bitmap) : Preview
}

val Preview.data: Any
    get() = when (this) {
        is Preview.Local -> file
        is Preview.Uri -> uri
        is Preview.Bytes -> buffer
        is Preview.Decoded -> bitmap
    }
