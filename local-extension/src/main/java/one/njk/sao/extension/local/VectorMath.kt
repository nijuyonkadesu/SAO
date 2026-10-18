package one.njk.sao.extension.local

/** Small streaming numerical kernel; JNI avoids interpreting 512 buffer reads for every image. */
internal object VectorMath {
    init { System.loadLibrary("sao_vectors") }
    private external fun create(query: FloatArray): Long
    private external fun dot(handle: Long, vector: ByteArray): Double
    private external fun destroy(handle: Long)
    class Scorer(query: FloatArray) : AutoCloseable {
        private var handle=create(query).also { check(it!=0L) { "Could not allocate vector scorer" } }
        fun score(vector: ByteArray): Double { check(handle!=0L); return dot(handle,vector) }
        override fun close() { if(handle!=0L) { destroy(handle); handle=0 } }
    }
}
