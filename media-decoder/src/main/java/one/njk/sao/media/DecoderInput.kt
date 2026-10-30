package one.njk.sao.media

import java.io.Closeable
import java.nio.ByteBuffer

/** Request-owned, seekable byte input. Implementations may stream/cache without a complete local file. */
interface DecoderInput : Closeable {
    /** Fill remaining bytes or return -1 at EOF. The temporary buffer must not be retained. */
    @Throws(java.io.IOException::class)
    fun read(destination: ByteBuffer): Int
    /** POSIX SET/CUR/END (0/1/2), or AVSEEK_SIZE (65536); return -1 for unknown size. */
    @Throws(java.io.IOException::class)
    fun seek(offset: Long, whence: Int): Long
}
