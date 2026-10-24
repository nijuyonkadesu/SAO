package one.njk.sao.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.allowHardware
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Actual Coil requests and recycled Views, with source files available only inside a preview lease. */
class PreviewOwnershipDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val directory=File(app.cacheDir,"preview-ownership-${UUID.randomUUID()}").apply { mkdirs() }
    private class Source(private val directory: File) : MediaSource {
        val leases=ConcurrentHashMap<String,AtomicInteger>()
        val reads=AtomicInteger()
        override val identity=flowOf("fixture")
        override suspend fun owns(item: MediaItem)=true
        override suspend fun page(query: MediaQuery,cursor: String?)=MediaPage(emptyList())
        override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,block: suspend (MediaHandle)->T): T=error("Unused")
        override suspend fun <T> withPreview(item: MediaItem,block: suspend (Preview)->T): T {
            reads.incrementAndGet()
            val lease=leases.getOrPut(item.id) { AtomicInteger() }; lease.incrementAndGet()
            try { return block(Preview.Local(File(directory,item.id))) }
            finally { lease.decrementAndGet() }
        }
    }
    private fun media(id: String,color: Int): MediaItem {
        val bitmap=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { File(directory,id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
        return MediaItem(id,id,id,"image/png",emptyList(),emptyList())
    }
    private fun color(holder: MediaCarouselAdapter.Holder): Int? {
        val drawable=holder.binding.preview.drawable ?: return null
        val image=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888)
        return try { drawable.setBounds(0,0,24,24); drawable.draw(Canvas(image)); image.getPixel(12,12) }
        finally { image.recycle() }
    }
    private fun await(scenario: ActivityScenario<PickerActivity>,message: String,predicate: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+6000
        var ready=false
        while(!ready && SystemClock.elapsedRealtime()<deadline) { scenario.onActivity { ready=predicate() }; SystemClock.sleep(20) }
        assertTrue(message,ready)
    }

    @Test fun detachedAndRecycledCardsNeverRestartAnUnleasedPreview(): Unit {
        for(home in listOf(false,true)) exercise(home)
    }

    @Test fun rapidScrollKeepsEveryRenderedCardMatchedToItsCurrentMedia(): Unit {
        val source=Source(directory); val outside=AtomicInteger()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val loader=ImageLoader.Builder(app).allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).components {
            add(coil3.intercept.Interceptor { chain ->
                val id=(chain.request.data as File).name
                if(source.leases[id]?.get()?.let { it>0 }!=true) outside.incrementAndGet()
                delay(if(id.substringAfter("scroll-").toInt()%2==0) 90 else 10)
                chain.proceed()
            })
        }.build()
        val colors=List(50) { Color.rgb((it*31)%256,(it*67)%256,(it*127)%256) }
        val items=colors.mapIndexed { index,value -> media("scroll-$index",value) }
        val expected=items.mapIndexed { index,item -> item.id to colors[index] }.toMap()
        val adapter=MediaCarouselAdapter(loader,scope,source,select={})
        try { ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
            lateinit var view: RecyclerView
            scenario.onActivity { activity ->
                view=RecyclerView(activity).apply {
                    layoutManager=LinearLayoutManager(activity,RecyclerView.HORIZONTAL,false)
                    this.adapter=adapter
                    itemAnimator=null
                }
                activity.setContentView(view); adapter.submitList(items)
            }
            await(scenario,"Initial carousel did not load") { view.childCount>0 }
            repeat(35) { index ->
                scenario.onActivity { view.scrollToPosition((index*17)%items.size) }
                SystemClock.sleep(16)
            }
            scenario.onActivity { view.scrollToPosition(24) }
            await(scenario,"Visible cards never settled to their own images") {
                (0 until view.childCount).map { view.getChildViewHolder(view.getChildAt(it)) as MediaCarouselAdapter.Holder }
                    .let { cards -> cards.isNotEmpty() && cards.all { color(it)==expected[it.item?.id] } }
            }
            assertEquals("Scrolling restarted a request outside the source lease",0,outside.get())
            scenario.onActivity { view.adapter=null }
        } } finally {
            instrumentation.runOnMainSync { adapter.close(); scope.cancel(); loader.shutdown() }
            directory.deleteRecursively()
        }
    }
    private fun exercise(home: Boolean) {
        val source=Source(directory); val outside=AtomicInteger(); val decodes=AtomicInteger()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val loader=ImageLoader.Builder(app).allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).components {
            add(coil3.intercept.Interceptor { chain ->
                val id=(chain.request.data as File).name
                decodes.incrementAndGet()
                if(source.leases[id]?.get()!=1) outside.incrementAndGet()
                delay(30)
                chain.proceed()
            })
        }.build()
        val items=listOf(media("first",Color.RED),media("second",Color.BLUE))
        val adapter=MediaCarouselAdapter(loader,scope,source,home=home,select={})
        try { ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
            lateinit var parent: FrameLayout
            lateinit var holder: MediaCarouselAdapter.Holder
            val committed=CompletableDeferred<Unit>()
            scenario.onActivity { activity ->
                parent=FrameLayout(activity); activity.setContentView(parent)
                holder=adapter.onCreateViewHolder(parent,0)
                adapter.submitList(items) { committed.complete(Unit) }
            }
            runBlocking { withTimeout(5000) { committed.await() } }
            scenario.onActivity { adapter.onBindViewHolder(holder,0) }
            // Binding before attach is normal RecyclerView behaviour. It must still decode under its lease.
            await(scenario,"Detached bind did not finish its owned load") { holder.loading?.isCompleted==true }
            scenario.onActivity { parent.addView(holder.binding.root); adapter.onViewAttachedToWindow(holder) }
            await(scenario,"Initial preview must display red") { color(holder)==Color.RED }
            SystemClock.sleep(80)
            assertEquals("Coil restarted a view-owned request after the preview lease ended",0,outside.get())
            assertEquals("A bind should decode exactly once",1,decodes.get())
            repeat(12) { round ->
                val position=(round+1)%2
                scenario.onActivity {
                    adapter.onViewDetachedFromWindow(holder); parent.removeView(holder.binding.root)
                    adapter.onViewRecycled(holder); adapter.onBindViewHolder(holder,position)
                    parent.addView(holder.binding.root); adapter.onViewAttachedToWindow(holder)
                }
                await(scenario,"Recycled card displayed the wrong media") { color(holder)==if(position==0) Color.RED else Color.BLUE }
            }
            assertEquals("Every decode must retain its matching source lease",0,outside.get())
            assertEquals("Reattachment must not add stale decodes",13,decodes.get())
            scenario.onActivity { adapter.onViewDetachedFromWindow(holder); parent.removeView(holder.binding.root); adapter.onViewRecycled(holder) }
        } } finally {
            instrumentation.runOnMainSync { adapter.close(); scope.cancel(); loader.shutdown() }
            directory.listFiles()?.forEach { it.delete() }
        }
    }
}
