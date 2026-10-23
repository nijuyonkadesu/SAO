package one.njk.sao.floating

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.MainActivity
import one.njk.sao.R
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import one.njk.sao.viewmodels.ArtsViewModel
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.inject.Provider

/** Synthetic profiles only. A floating preference must never change the main screen's source. */
class FloatingSourcesDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private val access get()=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
    private fun profile(name: String)=SourceProfile(UUID.randomUUID().toString(),name,
        ExtensionConfig("fixture.source","fixture.source.Service",name,"1","Fixture"))
    private suspend fun isolated(block: suspend (SourceProfiles,ContextWrapper)->Unit) {
        val directory=File(app.cacheDir,"floating-sources-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object : ContextWrapper(app) { override fun getNoBackupFilesDir()=directory }
        try { block(SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec())),context) }
        finally { directory.deleteRecursively() }
    }
    @Test fun togglePersistsWithoutChangingProfilesSelectionCacheIdentityOrWritingOnNoOp(): Unit=runBlocking {
        isolated { profiles,context ->
            val a=profile("First"); val b=profile("Second")
            profiles.upsert(a,true); profiles.upsert(b); profiles.available(setOf(a.id,b.id)); profiles.enableAll(true)
            val original=profiles.current()
            profiles.enableInFloating(a.id,false)
            assertEquals(original,profiles.current())
            assertEquals(b.id,profiles.currentFloating().selectedId)
            assertEquals(listOf(b.id),(profiles.currentFloating().profiles.last().configuration as AllSourceConfiguration).members.map { it.id })
            val bytes=File(context.noBackupFilesDir,"source-profiles-v1").readBytes()
            profiles.enableInFloating(a.id,false)
            assertArrayEquals(bytes,File(context.noBackupFilesDir,"source-profiles-v1").readBytes())
            val fresh=SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
            assertEquals(original,fresh.current()); assertFalse(fresh.currentFloating().profiles.any { it.id==a.id })
            fresh.upsert(a.copy(name="Renamed",configuration=(a.configuration as ExtensionConfig).copy(revision="2")))
            assertFalse(fresh.currentFloating().profiles.any { it.id==a.id })
            fresh.enableInFloating(a.id,true)
            assertEquals(a.id,fresh.currentFloating().selectedId)
            fresh.enableInFloating(a.id,false); fresh.remove(a.id); fresh.upsert(a)
            assertTrue(fresh.currentFloating().profiles.any { it.id==a.id })
        }
    }
    private fun factory(slow: CompletableDeferred<Unit>?=null,release: CompletableDeferred<Unit>?=null): MediaSourceFactory=object : MediaSourceFactory {
        override fun create(profile: SourceProfile)=object : ManagedMediaSource {
            override val capabilities=flowOf(SourceCapabilities(setOf(SearchMode.DESCRIPTION),defaultMode=SearchMode.DESCRIPTION))
            override suspend fun page(query: MediaQuery,cursor: String?): MediaPage {
                if(query.text=="slow") { slow!!.complete(Unit); release!!.await() }
                return MediaPage(listOf(MediaItem("fixture_"+digest(profile.id),profile.name,"","image/png",emptyList(),emptyList(),
                    remote=RemoteMedia(profile.identity,profile.id,false))))
            }
            override suspend fun filters()=emptyList<String>()
            override val identity=flowOf(profile.identity)
            override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
            override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(textPreview(profile.name))
            override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                block: suspend (MediaHandle)->T): T=block(MediaHandle.Stream(profile.name.byteInputStream()))
            override fun close() { }
        }
    }
    @Test fun disablingAnActiveFloatingSourceCancelsOnlyItsRequest(): Unit=runBlocking {
        isolated { profiles,_ ->
            val a=profile("First"); val b=profile("Second")
            profiles.upsert(a,true); profiles.upsert(b); profiles.available(setOf(a.id,b.id))
            val mainEntered=CompletableDeferred<Unit>(); val floatingEntered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val main=SwitchingMediaSource(profiles,mapOf("extension" to Provider { factory(mainEntered,release) }),FloatingDispatchers())
            val floating=SwitchingMediaSource(profiles,mapOf("extension" to Provider { factory(floatingEntered,release) }),FloatingDispatchers(),floatingOnly=true)
            try { supervisorScope {
                val mainRead=async { main.page(MediaQuery("slow")) }
                val floatingRead=async { floating.page(MediaQuery("slow")) }
                withTimeout(5000) { mainEntered.await(); floatingEntered.await() }
                profiles.enableInFloating(a.id,false)
                try { withTimeout(5000) { floatingRead.await() }; fail("Floating read must cancel") }
                catch(error: CancellationException) { if(error is TimeoutCancellationException) throw error }
                assertTrue(mainRead.isActive); assertEquals(a.id,profiles.current().selectedId)
                release.complete(Unit)
                assertEquals("First",mainRead.await().items.single().title)
                assertEquals("Second",floating.page(MediaQuery()).items.single().title)
            } } finally { main.shutdown(); floating.shutdown() }
        }
    }
    @Test fun allExcludesDisabledSourcesOnlyInFloatingAndReenablingRestoresThem(): Unit=runBlocking {
        isolated { profiles,_ ->
            val a=profile("First"); val b=profile("Second")
            profiles.upsert(a); profiles.upsert(b); profiles.available(setOf(a.id,b.id)); profiles.enableAll(true); profiles.select(ALL_SOURCE_ID); profiles.selectFloating(ALL_SOURCE_ID)
            val factories=mapOf("extension" to Provider { factory() })
            val main=SwitchingMediaSource(profiles,factories,FloatingDispatchers())
            val floating=SwitchingMediaSource(profiles,factories,FloatingDispatchers(),floatingOnly=true)
            try {
                val identity=profiles.current().identity
                val old=floating.page(MediaQuery()).items.first { it.title=="First" }
                profiles.enableInFloating(a.id,false)
                assertEquals(identity,profiles.current().identity)
                assertEquals(setOf("First","Second"),main.page(MediaQuery()).items.map { it.title }.toSet())
                assertEquals(listOf("Second"),floating.page(MediaQuery()).items.map { it.title })
                assertFalse(floating.owns(old))
                profiles.enableInFloating(b.id,false)
                assertNull(profiles.currentFloating().selected)
                assertTrue(floating.page(MediaQuery()).items.isEmpty())
                assertEquals(2,main.page(MediaQuery()).items.size)
                profiles.enableInFloating(a.id,true)
                assertEquals(listOf("First"),floating.page(MediaQuery()).items.map { it.title })
                assertEquals(2,main.page(MediaQuery()).items.size)
            } finally { main.shutdown(); floating.shutdown() }
        }
    }
    @Test fun switchesKeepMainCollectionAndOfferManageWhenEveryFloatingSourceIsOff(): Unit=runBlocking {
        val profiles=access.sources(); val previous=profiles.snapshots.first()
        val loadingGate=java.util.concurrent.CountDownLatch(1)
        val server=MockWebServer().apply {
            dispatcher=object : Dispatcher() {
                override fun dispatch(request: RecordedRequest)=when(request.path) {
                    "/api/server/features" -> MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                    "/api/tags" -> MockResponse().setBody("[]")
                    else -> {
                        loadingGate.await(60,java.util.concurrent.TimeUnit.SECONDS)
                        MockResponse().setBody("""{"assets":{"items":[]}}""")
                    }
                }
            }; start()
        }
        try {
            profiles.restore(SourceCatalog())
            val row=immichProfile("Floating toggle test",ImmichConfig(server.url("/").toString(),"toggle-fixture-key"))
            val extensions=testExtensions(app)
            for(extension in extensions.installed()) { extensions.migrate(extension); extensions.refresh(extension) }
            profiles.upsert(row,true)
            profiles.current().profiles.filter { it.id!=row.id && it.id!=ALL_SOURCE_ID }.forEach { profiles.enableInFloating(it.id,false) }
            ActivityScenario.launch<SourceSettingsActivity>(android.content.Intent(app,SourceSettingsActivity::class.java)).use { scenario ->
                await(scenario,"Floating switch ready") { activity -> holder(activity,row.id)?.binding?.floatingEnabled?.isEnabled==true }
                scenario.onActivity { holder(it,row.id)!!.binding.floatingEnabled.performClick() }
                await(scenario,"Off without removing source") { activity ->
                    val state=ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value
                    !state.saving && row.id in state.floatingDisabled && holder(activity,row.id)?.binding?.floatingEnabled?.isChecked==false
                }
                scenario.onActivity { activity ->
                    assertTrue(holder(activity,row.id)!!.binding.edit.isEnabled)
                    assertEquals(row.id,ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value.catalog.selectedId)
                }
                pauseSourceVisual("floating-source-switches")
                scenario.recreate()
                await(scenario,"Off survives recreation/metadata refresh") { holder(it,row.id)?.binding?.floatingEnabled?.let { control -> control.isEnabled && !control.isChecked }==true }
            }
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
                await(scenario,"Empty floating picker explains disabled sources") { it.findViewById<View>(R.id.no_source).visibility==View.VISIBLE }
                scenario.onActivity { activity ->
                    assertEquals(app.getString(R.string.source_floating_empty_title),activity.findViewById<TextView>(R.id.no_source_title).text.toString())
                    assertEquals(app.getString(R.string.source_manage),activity.findViewById<TextView>(R.id.add_first_source).text.toString())
                }
                pauseSourceVisual("floating-sources-off")
            }
            ActivityScenario.launch<MainActivity>(android.content.Intent(app,MainActivity::class.java)).use { scenario ->
                await(scenario,"Carousel remains mounted without a loading overlay") { activity ->
                    ViewModelProvider(activity)[ArtsViewModel::class.java].state.value.browse.loading &&
                        activity.findViewById<View>(R.id.carousel_recycler_view)?.visibility==View.VISIBLE &&
                        activity.findViewById<View>(R.id.home_status)?.visibility==View.GONE &&
                        activity.findViewById<TextView>(R.id.type)?.text.toString().contains(row.name)
                }
                pauseSourceVisual("home-source-loading")
                loadingGate.countDown()
                await(scenario,"Main still searches a source disabled in floating") { !ViewModelProvider(it)[ArtsViewModel::class.java].state.value.browse.loading }
                scenario.onActivity { assertEquals(row.identity,ViewModelProvider(it)[ArtsViewModel::class.java].state.value.browse.sourceId) }
            }
        } finally {
            loadingGate.countDown()
            profiles.restore(previous)
            access.browseSession().reset(); server.shutdown()
        }
    }
    private fun holder(activity: SourceSettingsActivity,id: String): SourceListAdapter.Holder? {
        val list=activity.findViewById<RecyclerView>(R.id.source_list)
        val adapter=(list.adapter as androidx.recyclerview.widget.ConcatAdapter).adapters.filterIsInstance<SourceListAdapter>().single()
        val position=adapter.currentList.indexOfFirst { it.id==id }
        if(position<0) return null
        val holder=list.findViewHolderForAdapterPosition(position+1) as? SourceListAdapter.Holder
        if(holder==null) list.scrollToPosition(position+1)
        return holder
    }
    private fun <A: android.app.Activity> await(scenario: ActivityScenario<A>,label: String,condition: (A)->Boolean) {
        val until=SystemClock.elapsedRealtime()+15_000; var ready=false
        while(!ready && SystemClock.elapsedRealtime()<until) { scenario.onActivity { ready=condition(it) }; SystemClock.sleep(40) }
        assertTrue(label,ready)
    }
}
