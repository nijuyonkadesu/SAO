package one.njk.sao.floating

import kotlinx.coroutines.*
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test

class CarouselPreviewPrefetcherTest {
    private val key=PickerBrowseKey("source",MediaQuery(),null)
    private fun item(index: Int)=MediaItem("$index","$index","$index","image/png",emptyList(),emptyList(),
        remote=RemoteMedia("source","$index",false,cacheKeys=mapOf("preview" to "small-$index","original" to "full-$index")))
    @Test fun fourPreviewWindowHasTwoTransfersAndReusesCompletedItems(): Unit=runBlocking {
        val started=mutableListOf<String>(); val gates=List(8) { CompletableDeferred<Unit>() }
        val loader=CarouselPreviewPrefetcher(this) { row -> started+=row.id; gates[row.id.toInt()].await(); row.id }
        val rows=List(8,::item)
        loader.update(key,rows,0); yield(); assertEquals(listOf("0","1"),started)
        gates[0].complete(Unit); yield(); yield(); assertEquals(listOf("0","1","2"),started)
        gates[1].complete(Unit); gates[2].complete(Unit); gates[3].complete(Unit); repeat(4) { yield() }
        loader.update(key,rows,0); yield(); assertEquals(listOf("0","1","2","3"),started)
        loader.update(key,rows,1); yield(); assertEquals(listOf("0","1","2","3","4"),started)
        loader.pause()
    }
    @Test fun originalFallbacksAreSkippedAndPauseCancelsOutstandingTransfers(): Unit=runBlocking {
        val started=mutableListOf<String>(); var cancelled=false
        val loader=CarouselPreviewPrefetcher(this) { row -> started+=row.id
            try { awaitCancellation() } finally { cancelled=true }
        }
        val original=item(0).let { it.copy(remote=it.remote!!.copy(cacheKeys=mapOf("preview" to "full","original" to "full"))) }
        loader.update(key,listOf(original,item(1)),0); yield(); assertEquals(listOf("1"),started)
        loader.pause(); yield(); assertTrue(cancelled)
    }
    @Test fun failurePausesSpeculationUntilANewBrowseAndSourceChangesCancelOldWork(): Unit=runBlocking {
        var calls=0; var cancelled=false
        val loader=CarouselPreviewPrefetcher(this) { row ->
            calls++
            if(row.id=="0") error("Offline")
            try { awaitCancellation() } finally { cancelled=true }
        }
        val rows=List(8,::item)
        loader.update(key,rows,0); repeat(3) { yield() }; val failedCalls=calls
        loader.update(key,rows,2); yield(); assertEquals(failedCalls,calls)
        loader.update(key.copy(query=MediaQuery("new")),rows,1); yield(); assertTrue(calls>failedCalls)
        loader.update(key.copy(sourceId="another"),emptyList(),0); yield(); assertTrue(cancelled)
        loader.pause()
    }
}
