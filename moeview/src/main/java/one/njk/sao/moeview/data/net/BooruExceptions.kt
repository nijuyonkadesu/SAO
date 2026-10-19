package one.njk.sao.moeview.data.net

import one.njk.sao.moeview.data.Board
import java.io.IOException

/**
 * Raised when the server answers with a non-2xx status code.
 *
 * @param retryAfterMs server-provided wait (HTTP 429 `Retry-After`) when known, else -1.
 */
class HttpStatusException(val code: Int, val retryAfterMs: Long = -1L, val retryAfter: String? = null, val challenged: Boolean = false) :
    IOException("HTTP $code")

/** Raised when every configured transport for a board failed. */
class NoTransportException(board: Board, cause: Throwable) :
    IOException("No working transport for ${board.id} (edge + direct)", cause)

class InvalidMediaResponseException : IOException("Invalid response")
