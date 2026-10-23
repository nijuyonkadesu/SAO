package one.njk.sao.floating

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** Explicit local CLIP fixture, exercising unchanged host APIs and the real per-album buttons. */
class LocalContextSourceDeviceTest {
    @Test fun contextWorksAcrossProcessesAndAlbumControlsKeepTheOriginalLibrary()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val app=instrumentation.targetContext
        val access=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
        val extension=testExtensions(app).installed().firstOrNull { it.component.packageName=="one.njk.sao.extension.local" }
        Assume.assumeNotNull(extension)
        Assume.assumeNotNull(app.packageManager.resolveContentProvider("one.njk.sao.extension.local.fixtures",0))
        val fixture=ExtensionClient(app,extension!!.component).profiles().firstOrNull { it.id=="de89d2d4-3110-4fb4-894b-5cef5e304e70" }
        Assume.assumeNotNull(fixture)
        val profile=extension.profile(fixture!!)
        val previous=access.sources().current()
        try {
            access.sources().upsert(profile,true)
            val source=access.mediaSource()
            val capabilities=source.capabilities.first { it.modes.isNotEmpty() }
            Assume.assumeTrue(SearchMode.CONTEXT in capabilities.modes)
            assertEquals(SearchMode.CONTEXT,capabilities.defaultMode)
            val query=MediaQuery("a smiling face")
            val before=source.page(query).items
            assertTrue(before.isNotEmpty())
            for(mime in listOf("image/png","image/gif","video/mp4","video/webm")) {
                val item=before.first { it.mimeType==mime }
                val bytes=source.withRendition(item,Rendition.ORIGINAL) { handle -> handle.openStream().use { it.readBytes() } }
                assertTrue(bytes.isNotEmpty())
                if(item.isVideo) assertArrayEquals(bytes,
                    source.withRendition(item,Rendition.PLAYBACK) { handle -> handle.requireFile().readBytes() })
            }
            assertEquals(before.size,source.page(MediaQuery("👩🏽‍💻")).items.size)
            ExtensionForm(profile,extension).use { form ->
                form.await("context_search") { it.isChecked }
                form.await("processing_counts") { it.text.toString().contains("0 remaining") }
                pauseSourceVisual("local-album-progress")
                form.click("pause_folder"); form.await("pause_folder") { it.text.toString()=="Resume" }
                pauseSourceVisual("local-album-paused")
                form.click("pause_folder"); form.await("pause_folder") { it.text.toString()=="Pause" }
                form.click("restart_folder")
                withTimeout(8000) {
                    while(instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()==null) delay(25)
                }
                check(instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("android:id/button1").first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                form.await("pause_folder") { it.isEnabled }
                withTimeout(60_000) { while(source.page(query).items.size!=before.size) delay(100) }
                assertEquals(before.map { it.remote!!.assetId },source.page(query).items.map { it.remote!!.assetId })
            }
        } finally {
            if(InstrumentationRegistry.getArguments().getString("removeLocalFixture")=="true") {
                val config=profile.configuration as ExtensionConfig
                ExtensionClient(app,extension.component).request(config.profile,ExtensionProtocol.DELETE_PROFILE,org.json.JSONObject().put("revision",config.revision))
            }
            access.sources().restore(previous); access.browseSession().reset()
        }
    }
}
