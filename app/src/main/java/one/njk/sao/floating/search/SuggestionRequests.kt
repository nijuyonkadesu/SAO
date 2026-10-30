package one.njk.sao.floating.search

import kotlinx.coroutines.CancellationException
import one.njk.sao.source.ExtensionFailure
import one.njk.sao.source.SearchSuggestions
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Only failure cooldown metadata is retained. There is no result cache, timer or automatic retry. */
@Singleton
class SuggestionRequests @Inject constructor() {
    private val paused=linkedMapOf<String,Long>()
    suspend fun run(owner: String,cursor: Int,request: suspend ()->SearchSuggestions): SearchSuggestions {
        val now=System.nanoTime()/1_000_000
        synchronized(paused) {
            paused.entries.removeAll { it.value<=now }
            if((paused[owner] ?: 0)>now) return SearchSuggestions.empty(cursor)
        }
        try { return request() }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            val failure=generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ExtensionFailure>().firstOrNull()
            val wait=failure?.retryAfter?.let { value ->
                value.toLongOrNull()?.coerceIn(0,86400)?.times(1000) ?: runCatching {
                    (ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()).coerceIn(0,86_400_000)
                }.getOrNull()
            } ?: if(failure?.code=="HTTP_429") 30_000L else 5_000L
            synchronized(paused) {
                paused[owner]=System.nanoTime()/1_000_000+wait
                while(paused.size>64) paused.remove(paused.keys.first())
            }
            throw error
        }
    }
}
