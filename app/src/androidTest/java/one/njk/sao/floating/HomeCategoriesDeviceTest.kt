package one.njk.sao.floating

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.MainActivity
import one.njk.sao.R
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import one.njk.sao.viewmodels.ArtsViewModel
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/** All images, credentials and queries are synthetic localhost fixtures. */
class HomeCategoriesDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val access get()=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
    private fun profile(name: String)=SourceProfile(UUID.randomUUID().toString(),name,ExtensionConfig("fixture","fixture.Service",name,"1","Fixture"))
    @Test fun capabilityFailureShowsRetryAndExplicitRetryRecovers(): Unit=runBlocking {
        val profiles=access.sources(); val previous=profiles.snapshots.first()
        val status=AtomicInteger(401); val requests=AtomicInteger()
        val server=MockWebServer().apply {
            dispatcher=object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse=when(request.requestUrl?.encodedPath) {
                    "/api/server/features" -> {
                        requests.incrementAndGet()
                        MockResponse().setResponseCode(status.get()).setBody("""{"smartSearch":true,"ocr":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        try {
            profiles.restore(SourceCatalog())
            val row=immichProfile("Capability retry",ImmichConfig(server.url("/").toString(),"synthetic-key"))
            profiles.upsert(row,true)
            ActivityScenario.launch<HomeCategoriesActivity>(android.content.Intent(app,HomeCategoriesActivity::class.java)).use { scenario ->
                await(scenario,"Capability failure is visible") { model(it).state.value.error!=null }
                scenario.onActivity {
                    assertFalse(model(it).state.value.loaded)
                    assertEquals(View.VISIBLE,it.findViewById<View>(R.id.retry).visibility)
                    assertFalse(it.findViewById<View>(R.id.add_category).isEnabled)
                }
                val failedRequests=requests.get()
                assertTrue(failedRequests>0)
                delay(350)
                assertEquals("No idle capability retry",failedRequests,requests.get())
                status.set(200)
                scenario.onActivity { it.findViewById<View>(R.id.retry).performClick() }
                await(scenario,"Explicit retry loads capabilities") { model(it).state.value.let { state -> state.loaded && state.error==null } }
                scenario.onActivity {
                    assertEquals(View.GONE,it.findViewById<View>(R.id.retry).visibility)
                    assertTrue(it.findViewById<View>(R.id.add_category).isEnabled)
                }
                assertTrue(requests.get()>failedRequests)
            }
        } finally { profiles.restore(previous); server.shutdown() }
    }

    private suspend fun isolated(block: suspend (SourceProfiles,ContextWrapper)->Unit) {
        val dir=File(app.cacheDir,"home-categories-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object : ContextWrapper(app) { override fun getNoBackupFilesDir()=dir }
        try { block(SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec())),context) }
        finally { dir.deleteRecursively() }
    }
    @Test fun categoriesArePerProfileSurviveRevisionChangesAndDistinguishEmptyFromDefaults(): Unit=runBlocking {
        isolated { profiles,context ->
            val a=profile("One"); val b=profile("Two"); profiles.upsert(a,true); profiles.upsert(b)
            val rows=listOf(SourceCategory("custom","Blue hair",MediaQuery("blue_hair",mode=SearchMode.DESCRIPTION)))
            profiles.homeCategories(a.id,rows)
            val fresh=SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
            assertEquals(rows,fresh.snapshots.first().homeCategories[a.id]); assertNull(fresh.snapshots.first().homeCategories[b.id])
            fresh.upsert(a.copy(name="Renamed",configuration=(a.configuration as ExtensionConfig).copy(revision="2")))
            assertEquals(rows,fresh.snapshots.first().homeCategories[a.id]); assertEquals(a.id,fresh.current().selectedId)
            fresh.homeCategories(a.id,emptyList()); assertEquals(emptyList<SourceCategory>(),fresh.snapshots.first().homeCategories[a.id])
            fresh.homeCategories(a.id,null); assertNull(fresh.snapshots.first().homeCategories[a.id])
            fresh.homeCategories(a.id,rows); fresh.remove(a.id); assertNull(fresh.snapshots.first().homeCategories[a.id])
        }
    }
    @Test fun customCategoriesChangeOnlyHomeCapabilitiesWithoutRecreatingTheProvider(): Unit=runBlocking {
        isolated { profiles,_ ->
            val row=profile("One"); profiles.upsert(row,true)
            val defaults=listOf(SourceCategory("all","All")); var creates=0
            val factory=object : MediaSourceFactory {
                override fun create(profile: SourceProfile)=object : ManagedMediaSource {
                    init { creates++ }
                    override val capabilities=flowOf(SourceCapabilities(setOf(SearchMode.DESCRIPTION),categories=defaults,defaultMode=SearchMode.DESCRIPTION))
                    override suspend fun page(query: MediaQuery,cursor: String?)=MediaPage(emptyList())
                    override suspend fun filters()=emptyList<String>()
                    override val identity=flowOf(profile.identity)
                    override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                    override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(error("Unused"))
                    override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                        block: suspend (MediaHandle)->T): T=error("Unused")
                    override fun close() { }
                }
            }
            val factories=mapOf("extension" to Provider<MediaSourceFactory> { factory })
            val home=SwitchingMediaSource(profiles,factories,FloatingDispatchers())
            val floating=SwitchingMediaSource(profiles,factories,FloatingDispatchers(),floatingOnly=true)
            try {
                home.capabilities.first { it.categories==defaults }; floating.capabilities.first { it.categories==defaults }
                val before=creates; val identity=profiles.current().identity
                val custom=listOf(SourceCategory("blue","Blue",MediaQuery("blue_hair",mode=SearchMode.DESCRIPTION)))
                profiles.homeCategories(row.id,custom)
                withTimeout(5000) { home.capabilities.first { it.categories==custom } }
                assertEquals(defaults,floating.capabilities.first { it.sourceId==identity }.categories)
                assertEquals(before,creates); assertEquals(identity,profiles.current().identity)
            } finally { home.shutdown(); floating.shutdown() }
        }
    }
    @Test fun editorSavesExplicitlyAndMainRenameUpdatesChipsWithoutRefetchOrLostFocus(): Unit=runBlocking {
        val profiles=access.sources(); val previous=profiles.snapshots.first()
        val searches=AtomicInteger(); var lastQuery=""
        val png=instrumentation.context.assets.open("floating/happy.png").use { it.readBytes() }
        val server=MockWebServer().apply {
            dispatcher=object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse=when {
                    request.path=="/api/server/features" -> MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                    request.path=="/api/tags" -> MockResponse().setBody("[]")
                    request.path?.contains("/thumbnail")==true -> MockResponse().setBody(Buffer().write(png))
                    else -> {
                        searches.incrementAndGet(); lastQuery=JSONObject(request.body.readUtf8()).optString("query")
                        MockResponse().setBody("""{"assets":{"items":[{"id":"00000000-0000-0000-0000-000000000088","type":"IMAGE","originalMimeType":"image/png","originalFileName":"fixture.png","updatedAt":"2026-10-19T00:00:00Z"}]}}""")
                    }
                }
            }; start()
        }
        try {
            profiles.restore(SourceCatalog())
            val row=immichProfile("Home category fixture",ImmichConfig(server.url("/").toString(),"category-test-key"))
            for(extension in testExtensions(app).installed()) testExtensions(app).refresh(extension)
            profiles.upsert(row,true)
            ActivityScenario.launch<HomeCategoriesActivity>(android.content.Intent(app,HomeCategoriesActivity::class.java)).use { scenario ->
                await(scenario,"Editor capabilities") { model(it).state.value.loaded && model(it).state.value.profile?.id==row.id }
                val other=immichProfile("Other category fixture",ImmichConfig(server.url("/").toString(),"other-category-key"))
                profiles.upsert(other)
                scenario.onActivity { model(it).select(other.id) }
                await(scenario,"Editor changes its own source") { model(it).state.value.loaded && model(it).state.value.profile?.id==other.id }
                assertEquals(row.id,profiles.current().selectedId)
                scenario.onActivity { model(it).select(row.id) }
                await(scenario,"Editor returns to first source") { model(it).state.value.loaded && model(it).state.value.profile?.id==row.id }
                scenario.onActivity { it.findViewById<View>(R.id.add_category).performClick() }
                await(scenario,"Inline category editor") { it.findViewById<View>(R.id.editor).visibility==View.VISIBLE }
                val bytes=File(app.noBackupFilesDir,"source-profiles-v1").readBytes()
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.EditText>(R.id.label).setText("Favourite")
                    activity.findViewById<android.widget.EditText>(R.id.query).apply { requestFocus(); setText("blue shirt") }
                    androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView)
                        .show(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                await(scenario,"Query remains visible above keyboard") { activity ->
                    val field=activity.findViewById<View>(R.id.query)
                    val insets=androidx.core.view.ViewCompat.getRootWindowInsets(field)
                    val location=IntArray(2).also(field::getLocationOnScreen)
                    insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true &&
                        location[1]>=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top &&
                        location[1]+field.height<=activity.window.decorView.height-insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                }
                assertArrayEquals(bytes,File(app.noBackupFilesDir,"source-profiles-v1").readBytes())
                assertEquals(0,searches.get())
                scenario.onActivity { it.findViewById<View>(R.id.save_category).performClick() }
                await(scenario,"Category saved") { model(it).state.value.draft==null && model(it).state.value.categories.any { cat -> cat.label=="Favourite" } }
                scenario.recreate()
                await(scenario,"Category survives recreation") { model(it).state.value.categories.any { cat -> cat.label=="Favourite" } }
                pauseSourceVisual("home-categories-editor")
            }
            ActivityScenario.launch<MainActivity>(android.content.Intent(app,MainActivity::class.java)).use { scenario ->
                await(scenario,"Home custom chip") { !ViewModelProvider(it)[ArtsViewModel::class.java].state.value.browse.loading && chips(it).any { chip -> chip.text=="Favourite" } }
                scenario.onActivity { chips(it).first { chip -> chip.text=="Favourite" }.performClick() }
                await(scenario,"Custom query searched intact") { !ViewModelProvider(it)[ArtsViewModel::class.java].state.value.browse.loading && lastQuery=="blue shirt" }
                val before=searches.get(); var focus: MediaIdentity?=null
                scenario.onActivity { focus=ViewModelProvider(it)[ArtsViewModel::class.java].focusedId }
                val custom=profiles.snapshots.first().homeCategories.getValue(row.id)
                profiles.homeCategories(row.id,custom.map { if(it.label=="Favourite") it.copy(label="Renamed") else it })
                await(scenario,"Label updated") { chips(it).any { chip -> chip.text=="Renamed" } }
                assertEquals(before,searches.get())
                scenario.onActivity { assertEquals(focus,ViewModelProvider(it)[ArtsViewModel::class.java].focusedId) }
                profiles.homeCategories(row.id,emptyList()); await(scenario,"All chips may be removed") { chips(it).isEmpty() }
                profiles.homeCategories(row.id,null); await(scenario,"Reset source defaults") { chips(it).any { chip -> chip.text=="Happy" } }
            }
        } finally { profiles.restore(previous); access.browseSession().reset(); server.shutdown() }
    }
    private fun model(activity: HomeCategoriesActivity)=ViewModelProvider(activity)[HomeCategoriesViewModel::class.java]
    private fun chips(activity: MainActivity): List<Chip> = activity.findViewById<ChipGroup>(R.id.categories).let { group ->
        (0 until group.childCount).map { group.getChildAt(it) as Chip }
    }
    private fun <A: android.app.Activity> await(scenario: ActivityScenario<A>,label: String,condition: (A)->Boolean) {
        val until=SystemClock.elapsedRealtime()+15_000; var ready=false
        while(!ready && SystemClock.elapsedRealtime()<until) { scenario.onActivity { ready=condition(it) }; SystemClock.sleep(40) }
        assertTrue(label,ready)
    }
}
