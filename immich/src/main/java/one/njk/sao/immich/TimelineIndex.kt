package one.njk.sao.immich

import com.squareup.moshi.JsonReader
import kotlinx.coroutines.*
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.Buffer
import okio.buffer
import retrofit2.Call
import retrofit2.await
import java.util.concurrent.atomic.AtomicBoolean

/** Read only the ordered IDs. Large parallel thumbnail/date arrays never enter app memory. */
object TimelineIndex {
    suspend fun read(call: Call<ResponseBody>): List<String> = withContext(Dispatchers.IO) {
        val body = call.await()
        try { read(body) { call.cancel() } } finally { body.close() }
    }
    internal suspend fun read(body: ResponseBody, cancel: () -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        coroutineScope {
            body.use {
                val finished = AtomicBoolean()
                val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    // Cancel the HTTP call, not a concurrent source.close(): closing an Okio
                    // source while its read is blocked can corrupt its timeout bookkeeping.
                    try { awaitCancellation() } finally { if (!finished.get()) cancel() }
                }
                try {
                    val limit = 64L * 1024 * 1024
                    require(body.contentLength() <= limit) { "Timeline bucket exceeds metadata limit" }
                    val context = currentCoroutineContext()
                    val input = object : ForwardingSource(body.source()) {
                        var total = 0L
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            context.ensureActive()
                            return super.read(sink, byteCount).also {
                                if (it > 0) total += it
                                check(total <= limit) { "Timeline bucket exceeds metadata limit" }
                            }
                        }
                    }.buffer()
                    JsonReader.of(input).use { reader ->
                        var ids: List<String>? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            ensureActive()
                            if (reader.nextName() == "id") {
                                check(ids == null) { "Duplicate timeline ID field" }
                                val values = ArrayList<String>()
                                reader.beginArray()
                                while (reader.hasNext()) { ensureActive(); values += reader.nextString() }
                                reader.endArray(); ids = values
                            } else reader.skipValue()
                        }
                        reader.endObject()
                        check(reader.peek() == JsonReader.Token.END_DOCUMENT)
                        requireNotNull(ids) { "Timeline response has no ID array" }
                    }
                } catch (error: Exception) {
                    // Socket cancellation interrupts blocking reads with IOException. Preserve
                    // coroutine cancellation so it cannot become a retry or a parent failure.
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally { finished.set(true); closer.cancel() }
            }
        }
    }
}
