package one.njk.sao.importing

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import one.njk.sao.floating.*
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real source-row navigation, extension-owned UI, reverse IPC and host-owned SAF/transfer work. */
class InlineSourceSettingsDeviceTest {
    @Test fun editingAProfileShowsSettingsAndLiveImportsTogetherWithoutLeakingOtherProfiles()=runBlocking {
        NativeImportFixtures().use { fixture ->
            val app=fixture.app
            fixture.instrumentation.uiAutomation.serviceInfo=fixture.instrumentation.uiAutomation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
            val previous=access.sources().snapshots.first()
            val permissionReads=AtomicInteger()
            val server=MockWebServer().apply { dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse=when(request.requestUrl!!.encodedPath) {
                    "/api/api-keys/me" -> { permissionReads.incrementAndGet(); MockResponse().setBody("""{"id":"fixture","name":"fixture","permissions":["all"]}""") }
                    "/api/assets/bulk-upload-check" -> {
                        val id=JSONObject(request.body.readUtf8()).getJSONArray("assets").getJSONObject(0).getString("id")
                        MockResponse().setBody("""{"results":[{"id":"$id","action":"accept"}]}""")
                    }
                    "/api/assets" -> MockResponse().setBody("""{"id":"inline-fixture","status":"created"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }; start() }
            val profile=immichProfile("Reaction library",ImmichConfig(server.url("/").toString(),"inline-fixture-key"))
            access.sources().upsert(profile)
            val config=profile.configuration as ExtensionConfig
            val media=fixture.image("reaction.png",Color.BLUE)
            fun node(id: String)=fixture.instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("${config.packageName}:id/$id")?.firstOrNull()
            suspend fun await(label: String,predicate: ()->Boolean) {
                try { withTimeout(15_000) { while(!predicate()) delay(50) } }
                catch(error: TimeoutCancellationException) { throw AssertionError("$label; intake=${node("intake_error")?.text}",error) }
                assertTrue(label,predicate())
            }
            fun setText(id: String,value: String) {
                check(node(id)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
                }))
            }
            try {
                try {
                    ExtensionClient(app,ImportSettings.service(app.packageName)).request(config.profile,ImportSettings.STATE,JSONObject().put("revision",config.revision))
                    fail("A same-signature caller must not read another APK's profile")
                } catch(error: ExtensionFailure) { assertEquals("PROFILE_MISSING",error.code) }
                try { app.imports().settingsSource(config.packageName,config.profile,"stale"); fail("Accepted stale settings") }
                catch(error: ExtensionFailure) { assertEquals("PROFILE_CHANGED",error.code) }
                ActivityScenario.launch<SourceSettingsActivity>(Intent(app,SourceSettingsActivity::class.java)).use {
                    fixture.click("Edit ${profile.name}")
                    await("Editing must open the extension directly") { node("server_url")!=null && node("api_key")!=null }
                    await("Inline intake must load") { node("intake_add_media")?.isEnabled==true }
                    assertEquals(1,permissionReads.get())
                    pauseSourceVisual("inline-immich-config")
                    setText("api_key","unsaved-key")
                    await("Unsaved credentials must disable imports") { node("intake_add_media")?.isEnabled==false }
                    setText("api_key","inline-fixture-key")
                    await("Reverting the draft restores imports") { node("intake_add_media")?.isEnabled==true }
                    assertEquals("Draft edits must not cause permission polling",1,permissionReads.get())
                    node("intake_connect_folder")!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    check(node("intake_connect_folder")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    fixture.chooseFolder()
                    await("Host must own the connected-folder mapping") { app.imports().store.routes(profile.id).size==1 }
                    app.imports().scan(); app.imports().transfer(true)
                    await("Queue counts must update in the existing extension screen") { node("intake_status")?.text?.toString()?.contains("1 added")==true }
                    assertArrayEquals(media.second,app.contentResolver.openInputStream(media.first)!!.use { it.readBytes() })
                    assertEquals(2,permissionReads.get())
                    app.imports().pause(profile.id)
                    await("Pause state must appear inline") { node("intake_pause")?.text?.toString()=="Resume" }
                    check(node("intake_pause")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await("Inline Resume must control the host queue") { !app.imports().store.stats(profile.id).paused }
                    node("intake_connect_folder")!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    delay(250); pauseSourceVisual("inline-immich-imports")
                    fixture.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    repeat(4) { app.imports().store.added() }
                    delay(700)
                    assertEquals("Hidden settings must not query backend permissions",2,permissionReads.get())
                }
                ExtensionForm(profile).use { form ->
                    form.set("source_name","Renamed reactions")
                    form.click("back")
                    fixture.click("Save source")
                    // Wait for the result callback, rather than a transient empty accessibility window.
                    withTimeout(15_000) { while(access.sources().current().profiles.none { it.id==profile.id && it.name=="Renamed reactions" }) delay(50) }
                    assertEquals("Renamed reactions",ExtensionClient(app,config.component).profiles().single { it.id==config.profile }.name)
                }
            } finally {
                app.imports().store.routes(profile.id).forEach { app.imports().remove(it) }
                app.imports().store.writableDatabase.delete("tasks","source=?",arrayOf(profile.id))
                access.sources().restore(previous); server.shutdown()
            }
        }
    }
}
