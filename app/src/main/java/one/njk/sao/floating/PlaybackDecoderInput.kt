package one.njk.sao.floating

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import one.njk.sao.media.DecoderInput
import java.nio.ByteBuffer

/** FFmpeg and ExoPlayer share the same source/cache policy, including byte-range seeks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackDecoderInput(private val factory: DataSource.Factory, private val uri: Uri, private val owner: Job) : DecoderInput {
    private var source: DataSource? = null
    private var cursor = 0L
    private var total = -1L
    private val buffer = ByteArray(64 * 1024)
    init { open() }
    private fun open() {
        owner.ensureActive()
        val created = factory.createDataSource()
        try {
            val length = created.open(DataSpec.Builder().setUri(uri).setPosition(cursor).build())
            if (length >= 0) total = cursor + length
            source = created
        } catch (error: Throwable) { created.close(); throw error }
    }
    override fun read(destination: ByteBuffer): Int {
        owner.ensureActive()
        if (source == null) open()
        val count = requireNotNull(source).read(buffer, 0, minOf(buffer.size, destination.remaining()))
        if (count == C.RESULT_END_OF_INPUT) { total = cursor; return -1 }
        destination.put(buffer, 0, count); cursor += count
        return count
    }
    override fun seek(offset: Long, whence: Int): Long {
        owner.ensureActive()
        if (whence == 65536) return total
        val next = when (whence) { 0 -> offset; 1 -> cursor + offset; 2 -> if (total < 0) return -1 else total + offset; else -> return -1 }
        if (next < 0 || total >= 0 && next > total) return -1
        if (next != cursor) { source?.close(); source = null; cursor = next }
        return cursor
    }
    override fun close() { source?.close(); source = null }
}
