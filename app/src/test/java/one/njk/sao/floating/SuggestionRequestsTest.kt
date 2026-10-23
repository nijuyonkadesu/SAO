package one.njk.sao.floating

import kotlinx.coroutines.*
import one.njk.sao.floating.search.SuggestionRequests
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test

class SuggestionRequestsTest {
    @Test fun successfulQueriesAreNeverCached(): Unit=runBlocking {
        val requests=SuggestionRequests(); var calls=0
        repeat(2) { requests.run("profile",2) { calls++; SearchSuggestions.empty(2) } }
        assertEquals(2,calls)
    }
    @Test fun serverCooldownSuppressesNewQueriesForOnlyThatProfileWithoutRetrying(): Unit=runBlocking {
        val requests=SuggestionRequests(); var calls=0
        try { requests.run("one",2) { calls++; throw SourceReadException(0,ExtensionFailure("HTTP_429","60")) } }
        catch(_: SourceReadException) { }
        repeat(3) { requests.run("one",3+it) { calls++; SearchSuggestions.empty(3+it) } }
        assertEquals(1,calls)
        requests.run("two",2) { calls++; SearchSuggestions.empty(2) }; assertEquals(2,calls)
    }
    @Test fun cancellationDoesNotStartACooldownOrRetry(): Unit=runBlocking {
        val requests=SuggestionRequests(); var calls=0
        try { requests.run("profile",2) { calls++; throw CancellationException("Typing continued") } }
        catch(_: CancellationException) { }
        requests.run("profile",3) { calls++; SearchSuggestions.empty(3) }; assertEquals(2,calls)
    }
}
