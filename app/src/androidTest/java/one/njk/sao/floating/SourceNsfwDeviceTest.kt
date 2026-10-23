package one.njk.sao.floating

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.database.SettingsDataStore
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.inject.Provider

class SourceNsfwDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile(name: String,nsfw: Boolean=false)=SourceProfile(UUID.randomUUID().toString(),name,
        ExtensionConfig("fixture.source","fixture.source.Service",name,"1","Fixture"),nsfw)
    private suspend fun isolated(block: suspend (SourceProfiles,ContextWrapper,SettingsDataStore)->Unit) {
        val directory=File(app.cacheDir,"nsfw-sources-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object : ContextWrapper(app) { override fun getNoBackupFilesDir()=directory }
        val settings=SettingsDataStore(app); val before=settings.currentModeFlow().first()
        try {
            if(!before) settings.toggleMode()
            block(SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec())),context,settings)
        } finally {
            if(settings.currentModeFlow().first()!=before) settings.toggleMode()
            directory.deleteRecursively()
        }
    }
    @Test fun optionalPublicFlagRoundTripsAndOlderProfilesRemainCompatible() {
        val old=JSONObject().put("id","profile").put("name","Source").put("revision","1")
        assertFalse(SourceWire.profile(old).nsfw)
        val marked=SourceWire.profile(old.put("nsfw",true))
        assertEquals(marked,SourceWire.profile(SourceWire.profile(marked)))
        val extension=InstalledSourceExtension(android.content.ComponentName("fixture.source","fixture.source.Service"),"Fixture","Settings",emptySet())
        assertTrue(extension.profile(marked).nsfw)
    }
    @Test fun policyExcludesBlockedAndUnavailableMembersFromBothBrowsersAndAll() {
        val safe=profile("Safe"); val adult=profile("Marked",true); val missing=profile("Missing")
        val raw=SourceCatalog(listOf(safe,adult,missing),adult.id).withAllSource(true)
        val snapshot=SourceProfilesSnapshot(raw,available=setOf(safe.id,adult.id),floatingSelectedId=adult.id,nsfwAllowed=false)
        assertEquals(setOf(safe.id,ALL_SOURCE_ID),snapshot.browsingCatalog.profiles.map { it.id }.toSet())
        assertEquals(safe.id,snapshot.browsingCatalog.selectedId)
        assertEquals(safe.id,snapshot.floatingCatalog.selectedId)
        for(catalog in listOf(snapshot.browsingCatalog,snapshot.floatingCatalog)) {
            assertEquals(listOf(safe),(catalog.profiles.last().configuration as AllSourceConfiguration).members)
        }
        val onlyAdult=SourceProfilesSnapshot(SourceCatalog(listOf(adult),adult.id).withAllSource(true),nsfwAllowed=false)
        assertTrue(onlyAdult.browsingCatalog.profiles.isEmpty()); assertTrue(onlyAdult.floatingCatalog.profiles.isEmpty())
    }
    @Test fun globalModePreservesSavedChoicesAndDoesNotRewriteProfilesOrEraseManualDisables(): Unit=runBlocking {
        isolated { profiles,context,settings ->
            val safe=profile("Safe"); val adult=profile("Marked",true); val disabled=profile("Manually off",true)
            profiles.upsert(safe); profiles.upsert(adult); profiles.upsert(disabled)
            profiles.select(adult.id); profiles.selectFloating(adult.id); profiles.enableInFloating(disabled.id,false)
            profiles.enableAll(true)
            val before=File(context.noBackupFilesDir,"source-profiles-v1").readBytes()
            settings.toggleMode()
            assertEquals(safe.id,profiles.currentBrowsing().selectedId); assertEquals(safe.id,profiles.currentFloating().selectedId)
            profiles.select(disabled.id); profiles.selectFloating(disabled.id); profiles.enableInFloating(disabled.id,true)
            assertEquals(adult.id,profiles.current().selectedId)
            assertArrayEquals(before,File(context.noBackupFilesDir,"source-profiles-v1").readBytes())
            assertTrue(profiles.snapshots.first().floatingDisabled.contains(disabled.id))
            val fresh=SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
            assertTrue(fresh.current().profiles.first { it.id==adult.id }.nsfw)
            assertEquals(safe.id,fresh.currentBrowsing().selectedId)
            settings.toggleMode()
            assertEquals(adult.id,profiles.currentBrowsing().selectedId); assertEquals(adult.id,profiles.currentFloating().selectedId)
            assertFalse(profiles.currentFloating().profiles.any { it.id==disabled.id })
            assertArrayEquals(before,File(context.noBackupFilesDir,"source-profiles-v1").readBytes())
        }
    }
    @Test fun turningModeOffCancelsAnActiveBlockedSessionAndReadsTheSafeSource(): Unit=runBlocking {
        isolated { profiles,_,settings ->
            val safe=profile("Safe"); val adult=profile("Marked",true)
            profiles.upsert(safe); profiles.upsert(adult,true); profiles.selectFloating(adult.id)
            for(floating in listOf(false,true)) {
                if(!settings.currentModeFlow().first()) settings.toggleMode()
                val entered=CompletableDeferred<Unit>(); val closed=CompletableDeferred<Unit>()
                val factory=object : MediaSourceFactory {
                    override fun create(profile: SourceProfile)=object : ManagedMediaSource {
                        override suspend fun page(query: MediaQuery,cursor: String?): MediaPage {
                            if(profile.nsfw) { entered.complete(Unit); awaitCancellation() }
                            return MediaPage(emptyList())
                        }
                        override fun close() { if(profile.nsfw) closed.complete(Unit) }
                        override suspend fun filters()=emptyList<String>()
                        override val identity=kotlinx.coroutines.flow.flowOf(profile.identity)
                        override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                        override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(error("unused"))
                        override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                            block: suspend (MediaHandle)->T): T=error("unused")
                    }
                }
                val router=SwitchingMediaSource(profiles,mapOf("extension" to Provider<MediaSourceFactory> { factory }),FloatingDispatchers(),floating)
                try {
                    val pending=async { router.page(MediaQuery()) }
                    withTimeout(5000) { entered.await() }; settings.toggleMode()
                    withTimeout(5000) { closed.await() }
                    try { pending.await(); fail("Blocked request completed") } catch(_: CancellationException) { }
                    assertEquals(safe.identity,router.identity.first())
                    assertTrue(router.page(MediaQuery()).items.isEmpty())
                } finally { router.shutdown() }
            }
        }
    }
    @Test fun realExtensionFlagLocksItsSwitchButLeavesItsSettingsReachable(): Unit=runBlocking {
        val access=dagger.hilt.android.EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
        val profiles=access.sources(); val previous=profiles.snapshots.first()
        val settings=SettingsDataStore(app); val priorMode=settings.currentModeFlow().first()
        val extensions=testExtensions(app); val moeview=extensions.installed().single { it.component.packageName=="one.njk.sao.extension.moeview" }
        var created: SourceProfile?=null
        fun clickText(text: String) {
            val until=android.os.SystemClock.elapsedRealtime()+8000
            while(android.os.SystemClock.elapsedRealtime()<until) {
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
                var node=automation.windows.flatMap { it.root?.findAccessibilityNodeInfosByText(text).orEmpty() }
                    .firstOrNull { it.text?.toString()==text && it.isVisibleToUser }
                while(node!=null && !node.isClickable) node=node.parent
                if(node!=null && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
                android.os.SystemClock.sleep(40)
            }
            error("Missing control: $text")
        }
        try {
            profiles.restore(SourceCatalog())
            if(!priorMode) settings.toggleMode()
            ExtensionForm(installed=moeview).use { form ->
                form.set("source_name","Moeview · non-safe")
                form.await("rating")
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                val field=automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("${moeview.component.packageName}:id/rating").first()
                val bounds=android.graphics.Rect(); field.getBoundsInScreen(bounds)
                val time=android.os.SystemClock.uptimeMillis()
                for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                    val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,(bounds.right-bounds.height()/2).toFloat(),bounds.centerY().toFloat(),0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    automation.injectInputEvent(event,true); event.recycle()
                }
                clickText("Non-safe only")
                form.click("save_connection")
                withTimeout(10000) { while(profiles.current().profiles.none { it.name=="Moeview · non-safe" }) delay(40) }
            }
            val row=profiles.current().profiles.single { it.name=="Moeview · non-safe" }; created=row
            assertTrue(row.nsfw); settings.toggleMode()
            androidx.test.core.app.ActivityScenario.launch<SourceSettingsActivity>(android.content.Intent(app,SourceSettingsActivity::class.java)).use { scenario ->
                fun holder(activity: SourceSettingsActivity): SourceListAdapter.Holder? {
                    val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.source_list)
                    return (0 until list.childCount).mapNotNull { list.getChildViewHolder(list.getChildAt(it)) as? SourceListAdapter.Holder }
                        .firstOrNull { it.binding.name.text.toString()==row.name }
                }
                suspend fun await(blocked: Boolean) { withTimeout(15000) {
                    while(true) {
                        var ready=false
                        scenario.onActivity { activity -> ready=holder(activity)?.binding?.floatingEnabled?.let { !it.isEnabled==blocked && !it.isChecked==blocked }==true }
                        if(ready) break
                        delay(40)
                    }
                } }
                await(true)
                scenario.onActivity { activity ->
                    val binding=holder(activity)!!.binding
                    assertTrue(binding.edit.isEnabled); assertFalse(binding.root.isEnabled)
                    assertTrue(binding.description.text.toString().contains("NSFW Mode"))
                    val state=androidx.lifecycle.ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value
                    assertFalse(state.browsingCatalog.profiles.any { it.id==row.id }); assertTrue(state.floatingCatalog.profiles.isEmpty())
                }
                pauseSourceVisual("nsfw-source-disabled")
                settings.toggleMode(); await(false)
                scenario.onActivity { activity ->
                    val state=androidx.lifecycle.ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value
                    assertTrue(state.browsingCatalog.profiles.any { it.id==row.id }); assertTrue(state.floatingCatalog.profiles.any { it.id==row.id })
                }
            }
            val local=extensions.installed().single { it.component.packageName=="one.njk.sao.extension.local" }
            ExtensionForm(installed=local).use { form ->
                form.set("source_name","Personal stickers"); form.click("nsfw")
                pauseSourceVisual("local-nsfw-setting")
                form.click("back"); clickText("Discard changes")
            }
        } finally {
            if(settings.currentModeFlow().first()!=priorMode) settings.toggleMode()
            created?.let { row -> val config=row.configuration as ExtensionConfig
                ExtensionClient(app,moeview.component).request(config.profile,ExtensionProtocol.DELETE_PROFILE,JSONObject().put("revision",config.revision)) }
            profiles.restore(previous)
        }
    }
    @Test fun blockedRowsExplainWhyAndKeepEditingAvailable() {
        val adult=profile("Marked",true)
        val row=app.sourceRows(SourceCatalog(listOf(adult),adult.id),nsfwAllowed=false).single()
        assertTrue(row.blocked); assertFalse(row.floatingEnabled); assertFalse(row.selected)
        assertTrue(row.interactive); assertTrue(row.description.contains("NSFW Mode"))
        val enabled=app.sourceRows(SourceCatalog(listOf(adult),adult.id),nsfwAllowed=true).single()
        assertFalse(enabled.blocked); assertTrue(enabled.floatingEnabled)
    }
}
