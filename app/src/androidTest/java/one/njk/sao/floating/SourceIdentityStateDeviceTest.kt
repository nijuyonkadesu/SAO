package one.njk.sao.floating

import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.R
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider

class SourceIdentityStateDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun profile(name: String)=SourceProfile(UUID.randomUUID().toString(),name,
        ExtensionConfig("fixture","fixture.Service",name,"1","Fixture"))
    private fun item(owner: String)=MediaItem("same-provider-id","Title","","image/png",emptyList(),emptyList(),
        remote=RemoteMedia(owner,"same-asset-id",false))
    private class Source(private val profile: SourceProfile,val status: StateFlow<SourceCapabilitiesState>): ManagedMediaSource {
        override val identity=flowOf(profile.identity)
        override val capabilitiesState=status
        override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
        override suspend fun page(query: MediaQuery,cursor: String?): MediaPage {
            (status.value as? SourceCapabilitiesState.Failed)?.let { throw it.error }
            return MediaPage(listOf(MediaItem("same-provider-id",profile.name,"","image/png",emptyList(),emptyList(),
                remote=RemoteMedia(profile.identity,"same-asset-id",false))))
        }
        override suspend fun <T> withPreview(item: MediaItem,block: suspend (Preview)->T): T=error("Unused preview")
        override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,block: suspend (MediaHandle)->T): T=error("Unused rendition")
        override fun close()=Unit
    }

    @Test fun equalProviderIdsHaveIndependentSelectionAndDiffMoves() {
        val first=item("owner-one"); val second=item("owner-two")
        val source=Source(profile("Unused"),MutableStateFlow(SourceCapabilitiesState.Ready(SourceCapabilities())))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val loader=ImageLoader.Builder(app).build()
        lateinit var adapter: MediaCarouselAdapter
        lateinit var firstHolder: MediaCarouselAdapter.Holder
        lateinit var secondHolder: MediaCarouselAdapter.Holder
        var adapterCreated=false
        var holdersCreated=false
        val moves=java.util.concurrent.atomic.AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                val parent=FrameLayout(ContextThemeWrapper(app,R.style.Theme_SAO))
                adapter=MediaCarouselAdapter(loader,scope,source) { }
                adapterCreated=true
                adapter.submitList(listOf(first,second))
                firstHolder=adapter.onCreateViewHolder(parent,0); secondHolder=adapter.onCreateViewHolder(parent,0)
                holdersCreated=true
                adapter.onBindViewHolder(firstHolder,0); adapter.onBindViewHolder(secondHolder,1)
                adapter.onViewAttachedToWindow(firstHolder); adapter.onViewAttachedToWindow(secondHolder)
                adapter.selectedId=second.identityKey
                assertFalse(firstHolder.itemView.isSelected); assertTrue(secondHolder.itemView.isSelected)
                adapter.focusedId=second.identityKey
                adapter.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeMoved(fromPosition: Int,toPosition: Int,itemCount: Int) { moves.addAndGet(itemCount) }
                })
            }
            val committed=CountDownLatch(1)
            instrumentation.runOnMainSync { adapter.submitList(listOf(second,first)) { committed.countDown() } }
            assertTrue("Diff must commit",committed.await(5,TimeUnit.SECONDS))
            assertTrue("Equal provider IDs from different owners must move as distinct rows",moves.get()>0)
            assertEquals(second.identityKey,adapter.focusedId)
        } finally {
            instrumentation.runOnMainSync {
                if(holdersCreated) { adapter.onViewRecycled(firstHolder); adapter.onViewRecycled(secondHolder) }
                if(adapterCreated) adapter.close()
                scope.cancel(); loader.shutdown()
            }
        }
    }

    @Test fun aggregateFailureKeepsHealthyPagesAndScopesHomeOverridesIndependently(): Unit=runBlocking {
        val directory=File(app.cacheDir,"source-state-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object: ContextWrapper(app) { override fun getNoBackupFilesDir()=directory }
        val profiles=SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
        val healthy=profile("Healthy"); val failed=profile("Failed")
        val defaults=listOf(SourceCategory("default","Default",MediaQuery("default",mode=SearchMode.DESCRIPTION)))
        val caps=SourceCapabilities(setOf(SearchMode.DESCRIPTION),defaultMode=SearchMode.DESCRIPTION,categories=defaults)
        val statuses=mapOf(healthy.id to MutableStateFlow<SourceCapabilitiesState>(SourceCapabilitiesState.Ready(caps)),
            failed.id to MutableStateFlow<SourceCapabilitiesState>(SourceCapabilitiesState.Failed(IllegalStateException("Unavailable"))))
        val factory=object: MediaSourceFactory { override fun create(profile: SourceProfile)=Source(profile,statuses.getValue(profile.id)) }
        val factories=mapOf("extension" to Provider<MediaSourceFactory> { factory })
        profiles.upsert(healthy,true); profiles.upsert(failed); profiles.enableAll(true); profiles.select(ALL_SOURCE_ID)
        profiles.selectFloating(healthy.id)
        val home=SwitchingMediaSource(profiles,factories,FloatingDispatchers())
        val floating=SwitchingMediaSource(profiles,factories,FloatingDispatchers(),floatingOnly=true)
        val observing=launch { home.capabilitiesState.collect() }
        val observingFloating=launch { floating.capabilitiesState.collect() }
        try {
            val owner=profiles.currentBrowsing().identity
            val failure=withTimeout(5000) { home.capabilitiesState.first { it is SourceCapabilitiesState.Failed } } as SourceCapabilitiesState.Failed
            assertEquals(owner,failure.available.sourceId); assertEquals(caps.modes,failure.available.modes)
            val page=home.page(MediaQuery(mode=SearchMode.DESCRIPTION))
            assertEquals(listOf(MediaIdentity(healthy.identity,"same-asset-id")),page.items.map { it.identityKey })
            assertTrue(page.interrupted)
            val custom=listOf(SourceCategory("custom","Custom",MediaQuery("custom",mode=SearchMode.DESCRIPTION)))
            profiles.homeCategories(ALL_SOURCE_ID,custom)
            withTimeout(5000) { home.capabilitiesState.first { it is SourceCapabilitiesState.Failed && it.available.categories==custom } }
            assertEquals(custom,withTimeout(5000) { home.capabilities.first { it.sourceId==owner && it.categories==custom } }.categories)
            val floatingReady=withTimeout(5000) { floating.capabilitiesState.first { it is SourceCapabilitiesState.Ready } } as SourceCapabilitiesState.Ready
            assertEquals(healthy.identity,floatingReady.value.sourceId); assertEquals(defaults,floatingReady.value.categories)
            assertEquals(ALL_SOURCE_ID,profiles.current().selectedId); assertEquals(healthy.id,profiles.currentFloating().selectedId)
        } finally {
            observing.cancelAndJoin(); observingFloating.cancelAndJoin(); home.shutdown(); floating.shutdown(); directory.deleteRecursively()
        }
    }
}
