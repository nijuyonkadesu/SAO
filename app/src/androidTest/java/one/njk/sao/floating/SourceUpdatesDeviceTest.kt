package one.njk.sao.floating

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SourceUpdatesDeviceTest {
    @Test fun indexUpdatesMergeWithoutMovingFocusAndUnsubscribeWhenHidden() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val access=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
        val updates=MutableSharedFlow<Unit>(extraBufferCapacity=1)
        val observers=AtomicInteger(); val reads=AtomicInteger()
        val first=MediaItem("first","First","floating/happy.png","image/png",emptyList(),emptyList())
        val second=first.copy(id="second",title="Second")
        var rows=listOf(first,second)
        val source=object : MediaSource {
            override val identity=flowOf(LOCAL_SOURCE_ID)
            override suspend fun owns(item: MediaItem)=true
            override val changes=flow {
                observers.incrementAndGet()
                try { emit(Unit); emitAll(updates) } finally { observers.decrementAndGet() }
            }
            override suspend fun page(query: MediaQuery,cursor: String?): MediaPage { reads.incrementAndGet(); return MediaPage(rows) }
            override suspend fun filters()=emptyList<String>()
            override suspend fun preview(item: MediaItem): Preview=
                Preview.Uri(android.net.Uri.parse("file:///android_asset/${item.asset}"))
            override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                block: suspend (MediaHandle)->T): T=block(MediaHandle.Stream(
                InstrumentationRegistry.getInstrumentation().context.assets.open(item.asset)))
        }
        lateinit var model: PickerViewModel
        val holder=ViewModelStore()
        instrumentation.runOnMainSync {
            model=PickerViewModel(source,access.preferences(),access.exports(),access.clipboard())
            holder.put("picker",model); model.pickerVisible(true)
        }
        val collecting=launch { model.state.collect() }
        suspend fun await(predicate: ()->Boolean) { withTimeout(8000) { while(!predicate()) delay(25) } }
        try {
            await { model.state.value.items.size==2 }
            instrumentation.runOnMainSync { model.focus("second") }
            rows=listOf(first.copy(title="Updated"),second,first.copy(id="third",title="Third")); updates.emit(Unit)
            await { model.state.value.items.size==3 }
            assertEquals("second",model.focusedId); assertEquals("Updated",model.state.value.items.first().title)
            instrumentation.runOnMainSync { model.pickerVisible(false) }
            await { observers.get()==0 }; val before=reads.get()
            rows=rows+first.copy(id="fourth"); updates.emit(Unit); delay(1300)
            assertEquals(before,reads.get())
            instrumentation.runOnMainSync { model.pickerVisible(true) }
            await { model.state.value.items.size==4 }; assertEquals("second",model.focusedId)
        } finally { collecting.cancel(); instrumentation.runOnMainSync { holder.clear() } }
    }
}
