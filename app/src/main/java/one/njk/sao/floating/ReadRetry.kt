package one.njk.sao.floating

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.random.Random

/** At most one delayed retry per user read. Never used by uploads, edits or other mutations. */
object ReadRetry {
    suspend fun <T> run(block: suspend () -> T): T {
        try { return block() }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException || !transient(error)) throw error
            val retryAfter = (error as? HttpException)?.response()?.headers()?.get("Retry-After")
            // Respect long/date-based server cooldowns by requiring a later explicit retry.
            val serverDelay = retryAfter?.toLongOrNull()?.takeIf { it in 0..5 }?.times(1000)
            if (retryAfter != null && serverDelay == null) throw error
            delay(maxOf(serverDelay ?: 0, Random.nextLong(500, 1001)))
            currentCoroutineContext().ensureActive()
            return block()
        }
    }
    private fun transient(error: Exception): Boolean = when (error) {
        is HttpException -> error.code() in setOf(408, 429, 502, 503, 504)
        is SSLException -> false
        is InterruptedIOException -> error is SocketTimeoutException
        is IOException -> true
        else -> false
    }
}
