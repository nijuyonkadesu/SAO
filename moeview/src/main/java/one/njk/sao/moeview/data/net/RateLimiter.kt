package one.njk.sao.moeview.data.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Simple per-host minimum-interval gate, used so bursts of requests (e.g.
 * fetches inside a loop, or the OkHttp retry path) never hammer an API.
 *
 * [acquire] blocks until the configured [minIntervalMs] has elapsed since the
 * previous [acquire]. It is safe to call concurrently.
 */
class RateLimiter(private val minIntervalMs: Long) {

    private val mutex = Mutex()
    private var lastRequestNs = 0L

    suspend fun acquire() {
        mutex.withLock {
            val now = System.nanoTime()
            if (lastRequestNs != 0L) {
                val elapsedMs = (now - lastRequestNs) / 1_000_000
                val remaining = minIntervalMs - elapsedMs
                if (remaining > 0) delay(remaining)
            }
            lastRequestNs = System.nanoTime()
        }
    }
}