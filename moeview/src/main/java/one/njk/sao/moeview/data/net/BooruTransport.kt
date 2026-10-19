package one.njk.sao.moeview.data.net

import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides which transports a board should try, in which order, and remembers
 * what worked last so we never repeatedly hit a known-bad transport.
 *
 * - The first success for a board becomes its preferred transport.
 * - A request that fails re-orders candidates so the *other* transport is tried
 *   first next time (self-healing: the next success settles it again).
 * - With [useEdgeTransport]=false, moeview edges are disabled entirely.
 * - With [allowTransportFallback]=false, only the preferred transport is used,
 *   so an edge-first board never silently falls back to a *direct* API that
 *   masks results (and vice versa).
 */
class BooruTransport(
    private val useEdgeTransport: Boolean,
    private val allowTransportFallback: Boolean,
) {

    private val preferred = ConcurrentHashMap<Board, Transport>()

    fun candidatesFor(board: Board): List<Transport> {
        val usable = board.usableTransports().filter { it == Transport.DIRECT || useEdgeTransport }
        if (usable.isEmpty()) return listOf(Transport.DIRECT)

        val start = preferred[board]
        val ordered = if (start != null && start in usable) {
            usable.sortedBy { if (it == start) 0 else 1 }
        } else {
            usable
        }
        // Without fallback, only the primary transport is ever attempted.
        return if (!allowTransportFallback) listOf(ordered.first()) else ordered
    }

    fun onSuccess(board: Board, transport: Transport) {
        preferred[board] = transport
    }

    fun onFailure(board: Board, transport: Transport) {
        board.usableTransports()
            .firstOrNull { it != transport }
            ?.let { preferred[board] = it }
    }

    fun reset() = preferred.clear()
}