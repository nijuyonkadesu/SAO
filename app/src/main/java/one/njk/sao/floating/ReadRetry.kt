package one.njk.sao.floating

import one.njk.sao.source.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            val retryAfter = (error as? ExtensionFailure)?.retryAfter
            // Respect long/date-based server cooldowns by requiring a later explicit retry.
            val serverDelay = retryAfter?.toLongOrNull()?.takeIf { it in 0..5 }?.times(1000)
            if (retryAfter != null && serverDelay == null) throw error
            delay(maxOf(serverDelay ?: 0, Random.nextLong(500, 1001)))
            currentCoroutineContext().ensureActive()
            return block()
        }
    }
    private fun transient(error: Exception): Boolean = when (error) {
        is ExtensionFailure -> error.code in setOf("NETWORK","DISCONNECTED") || error.code in setOf("HTTP_408","HTTP_429","HTTP_502","HTTP_503","HTTP_504")
        is SSLException -> false
        is InterruptedIOException -> error is SocketTimeoutException
        is IOException -> true
        else -> false
    }
}
