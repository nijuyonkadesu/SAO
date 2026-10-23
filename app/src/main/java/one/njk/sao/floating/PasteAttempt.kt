package one.njk.sao.floating

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class PasteReadiness { WAITING, NEEDS_FOCUS, READY, CHANGED }
enum class PasteOutcome { DISPATCHED, UNCONFIRMED, UNAVAILABLE, CANCELLED }

internal interface PasteEndpoint {
    fun inspect(): PasteReadiness
    fun focus(): Boolean
    fun paste(): Boolean
}

/** Wait for readiness, never retry Paste: a host can open its preview then return false. */
internal suspend fun attemptPaste(endpoint: PasteEndpoint, enabled: () -> Boolean,
    checks: Int = 24, pause: suspend () -> Unit = { delay(100) }): PasteOutcome {
    var requestedFocus = false
    repeat(checks) {
        currentCoroutineContext().ensureActive()
        if (!enabled()) return PasteOutcome.CANCELLED
        when (endpoint.inspect()) {
            PasteReadiness.CHANGED -> return PasteOutcome.UNAVAILABLE
            PasteReadiness.READY -> {
                currentCoroutineContext().ensureActive()
                if (!enabled()) return PasteOutcome.CANCELLED
                return if (endpoint.paste()) PasteOutcome.DISPATCHED else PasteOutcome.UNCONFIRMED
            }
            PasteReadiness.NEEDS_FOCUS -> if (!requestedFocus) {
                currentCoroutineContext().ensureActive()
                if (!enabled()) return PasteOutcome.CANCELLED
                requestedFocus = true
                endpoint.focus()
            }
            PasteReadiness.WAITING -> Unit
        }
        pause()
    }
    return PasteOutcome.UNAVAILABLE
}
