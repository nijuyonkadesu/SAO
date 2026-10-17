package one.njk.sao.floating

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PasteAttemptTest {
    private class Endpoint(var states: List<PasteReadiness>, val accepted: Boolean = true) : PasteEndpoint {
        var reads = 0; var focuses = 0; var pastes = 0
        var inspecting: () -> Unit = {}
        override fun inspect(): PasteReadiness { inspecting(); return states[minOf(reads++, states.lastIndex)] }
        override fun focus(): Boolean { focuses++; return true }
        override fun paste(): Boolean { pastes++; return accepted }
    }
    @Test fun waitsForWindowAndDelayedFocusThenPastesOnce() = runBlocking {
        val endpoint = Endpoint(listOf(PasteReadiness.WAITING, PasteReadiness.NEEDS_FOCUS,
            PasteReadiness.NEEDS_FOCUS, PasteReadiness.WAITING, PasteReadiness.READY))
        assertEquals(PasteOutcome.DISPATCHED, attemptPaste(endpoint, { true }, pause = {}))
        assertEquals(1, endpoint.focuses); assertEquals(1, endpoint.pastes); assertEquals(5, endpoint.reads)
    }
    @Test fun unconfirmedActionIsNeverReplayed() = runBlocking {
        val endpoint = Endpoint(listOf(PasteReadiness.READY), accepted = false)
        assertEquals(PasteOutcome.UNCONFIRMED, attemptPaste(endpoint, { true }, pause = {}))
        assertEquals(1, endpoint.pastes); assertEquals(1, endpoint.reads)
    }
    @Test fun changedEditorStopsWithoutFocusingOrPasting() = runBlocking {
        val endpoint = Endpoint(listOf(PasteReadiness.CHANGED))
        assertEquals(PasteOutcome.UNAVAILABLE, attemptPaste(endpoint, { true }, pause = {}))
        assertEquals(0, endpoint.focuses); assertEquals(0, endpoint.pastes)
    }
    @Test fun focusFailureHasABoundedWaitAndOneFocusRequest() = runBlocking {
        val endpoint = Endpoint(listOf(PasteReadiness.NEEDS_FOCUS))
        assertEquals(PasteOutcome.UNAVAILABLE, attemptPaste(endpoint, { true }, checks = 4, pause = {}))
        assertEquals(4, endpoint.reads); assertEquals(1, endpoint.focuses); assertEquals(0, endpoint.pastes)
    }
    @Test fun stopDuringInspectionCannotTriggerAFocusOrPasteAction() = runBlocking {
        for (state in listOf(PasteReadiness.NEEDS_FOCUS, PasteReadiness.READY)) {
            var active = true
            val endpoint = Endpoint(listOf(state)).apply { inspecting = { active = false } }
            assertEquals(PasteOutcome.CANCELLED, attemptPaste(endpoint, { active }, pause = {}))
            assertEquals(0, endpoint.focuses); assertEquals(0, endpoint.pastes)
        }
    }
    @Test fun cancellationDuringHandoffCannotPasteLater() = runBlocking {
        val endpoint = Endpoint(listOf(PasteReadiness.NEEDS_FOCUS, PasteReadiness.READY))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            attemptPaste(endpoint, { true }, pause = { awaitCancellation() })
        }
        job.cancelAndJoin()
        assertEquals(1, endpoint.focuses); assertEquals(0, endpoint.pastes)
    }
    @Test fun neverReturningWindowDoesNotCauseUnboundedReads() = runBlocking {
        val endpoint = Endpoint(listOf(PasteReadiness.WAITING))
        assertEquals(PasteOutcome.UNAVAILABLE, attemptPaste(endpoint, { true }, checks = 3, pause = {}))
        assertEquals(3, endpoint.reads); assertEquals(0, endpoint.focuses); assertEquals(0, endpoint.pastes)
    }
}
