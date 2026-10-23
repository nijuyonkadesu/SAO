package one.njk.sao.floating

import one.njk.sao.source.*

import java.nio.ByteBuffer

fun textPreview(value: String): Preview = Preview.Bytes(ByteBuffer.wrap(value.toByteArray()))

fun Preview.text(): String = when (this) {
    is Preview.Bytes -> {
        val copy = buffer.duplicate()
        String(ByteArray(copy.remaining()).also { copy.get(it) }, Charsets.UTF_8)
    }
    is Preview.Local -> file.readText()
    else -> error("Not a text preview")
}
