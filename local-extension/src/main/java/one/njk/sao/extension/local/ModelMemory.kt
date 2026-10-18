package one.njk.sao.extension.local

import java.nio.ByteBuffer

/** A short-lived read-only mapping of a granted SAF descriptor. No copied model and no GC-dependent unmap. */
internal object ModelMemory {
    init { System.loadLibrary("sao_vectors") }
    private external fun map(descriptor: Int): ByteBuffer
    private external fun unmap(buffer: ByteBuffer)
    class Mapping(descriptor: Int) : AutoCloseable {
        private var mapped: ByteBuffer?=map(descriptor)
        val bytes: ByteBuffer get()=checkNotNull(mapped).asReadOnlyBuffer()
        override fun close() { mapped?.let { unmap(it); mapped=null } }
    }
}
