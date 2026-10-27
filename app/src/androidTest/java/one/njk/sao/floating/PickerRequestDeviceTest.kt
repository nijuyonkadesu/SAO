package one.njk.sao.floating

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Delayed capability discovery must cancel old work before another source can be queried. */
class PickerRequestDeviceTest {
    private class Source: MediaSource {
        override val identity=MutableStateFlow("one")
        override val capabilities=MutableStateFlow(SourceCapabilities(setOf(SearchMode.CONTEXT),sourceId="one"))
        val calls=CopyOnWriteArrayList<Pair<String,MediaQuery>>()
        val started=Channel<Unit>(Channel.UNLIMITED)
        val cancelled=Channel<Unit>(Channel.UNLIMITED)
        var blocking=true
        override suspend fun page(query: MediaQuery,cursor: String?): MediaPage {
            calls+=identity.value to query
            started.send(Unit)
            if(blocking) try { awaitCancellation() } finally { cancelled.trySend(Unit) }
            return MediaPage(emptyList())
        }
        override suspend fun owns(item: MediaItem)=false
        override suspend fun <T> withPreview(item: MediaItem,block: suspend (Preview)->T): T=error("Unused")
        override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,block: suspend (MediaHandle)->T): T=error("Unused")
    }
    private suspend fun exercise(block: suspend (Source,PickerViewModel)->Unit) = coroutineScope {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val access=EntryPointAccessors.fromApplication(instrumentation.targetContext,FloatingTestAccess::class.java)
        val source=Source()
        val store=ViewModelStore()
        lateinit var model: PickerViewModel
        instrumentation.runOnMainSync {
            model=PickerViewModel(source,access.preferences(),access.exports(),access.clipboard())
            store.put("picker",model)
            model.pickerVisible(true)
        }
        val observing=launch(Dispatchers.Main.immediate) { model.state.collect {} }
        try { block(source,model) }
        finally { observing.cancelAndJoin(); instrumentation.runOnMainSync { store.clear() } }
    }
    @Test fun sourceChangesCancelRequestsWhileReplacementCapabilitiesArePending(): Unit=runBlocking {
        exercise { source,model ->
            withTimeout(3000) { source.started.receive() }
            source.identity.value="two"
            withTimeout(3000) { source.cancelled.receive() }
            assertEquals(listOf("one"),source.calls.map { it.first })
            val waiting=withTimeout(3000) { model.state.first { it.sourceId=="two" } }
            assertTrue(waiting.loading)
            source.blocking=false
            source.capabilities.value=source.capabilities.value.copy(sourceId="two")
            withTimeout(3000) { model.state.first { it.sourceId=="two" && !it.loading } }
            assertEquals(listOf("one","two"),source.calls.map { it.first })
        }
    }
    @Test fun unsupportedModesCancelRequestsUntilAValidModeIsChosen(): Unit=runBlocking {
        exercise { source,model ->
            withTimeout(3000) { source.started.receive() }
            source.capabilities.value=SourceCapabilities(setOf(SearchMode.OCR),sourceId="one",defaultMode=SearchMode.OCR)
            withTimeout(3000) { source.cancelled.receive() }
            assertEquals(1,source.calls.size)
            source.blocking=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.searchMode(SearchMode.OCR) }
            withTimeout(3000) { model.state.first { !it.loading && it.query.mode==SearchMode.OCR } }
            assertEquals(listOf(SearchMode.CONTEXT,SearchMode.OCR),source.calls.map { it.second.mode })
        }
    }
    @Test fun sourceSwitchUsesTheLatestTextDuringATypingDebounce(): Unit=runBlocking {
        exercise { source,model ->
            withTimeout(3000) { source.started.receive() }
            source.blocking=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.search("latest text")
                source.identity.value="two"
                source.capabilities.value=source.capabilities.value.copy(sourceId="two")
            }
            withTimeout(3000) { model.state.first { it.sourceId=="two" && !it.loading && it.query.text=="latest text" } }
            val replacement=source.calls.filter { it.first=="two" }
            assertEquals("Source switching must not send an obsolete debounced query",listOf("latest text"),replacement.map { it.second.text })
        }
    }
}
