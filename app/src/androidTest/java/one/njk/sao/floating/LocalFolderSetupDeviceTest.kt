package one.njk.sao.floating

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.util.UUID

/** Real system folder picker and persisted grants, including the optimized release extension. */
class LocalFolderSetupDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val automation get()=instrumentation.uiAutomation
    private fun node(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(root==null) return null
            if(predicate(root)) return root
            repeat(root.childCount) { find(root.getChild(it))?.let { result -> return result } }
            return null
        }
        return find(automation.rootInActiveWindow)
    }
    private fun waitFor(predicate: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12_000
        while(!predicate() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(80)
        assertTrue("System folder picker did not reach the expected control",predicate())
    }
    private fun label(value: String) = node { it.text?.toString()?.equals(value,true)==true || it.contentDescription?.toString()?.equals(value,true)==true }
    private fun clickable(value: String): AccessibilityNodeInfo? {
        fun ancestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo?=node
            while(current!=null && !current.isClickable) current=current.parent
            return current
        }
        val found=node { (it.text?.toString()?.equals(value,true)==true || it.contentDescription?.toString()?.equals(value,true)==true) && ancestor(it)!=null }
        return found?.let(::ancestor)
    }
    private fun click(value: String) {
        waitFor { clickable(value)!=null }
        val target=clickable(value)
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { "Could not click $value" }
    }
    @Test fun systemFolderGrantIndexesAutomaticallyAndSurvivesReopeningSettings()=runBlocking {
        val access=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
        val extensions=testExtensions(app)
        val extension=extensions.installed().firstOrNull { it.component.packageName=="one.njk.sao.extension.local" }
        Assume.assumeNotNull(extension)
        val folder="SAO-local-test-${UUID.randomUUID().toString().take(8)}"
        val name="Local album ${UUID.randomUUID().toString().take(8)}"
        val contents=linkedMapOf(
            "happy.png" to ("image/png" to app.assets.open("floating/happy.png").use { it.readBytes() }),
            "hello.gif" to ("image/gif" to app.assets.open("floating/hello.gif").use { it.readBytes() }),
            "preview-video.mp4" to ("video/mp4" to instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }),
            "preview-video.webm" to ("video/webm" to instrumentation.context.assets.open("preview-video.webm").use { it.readBytes() }))
        val files=mutableListOf<Uri>()
        val previous=access.sources().current()
        var created: SourceProfile?=null
        try {
            contents.forEach { (file,data) ->
                val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,file); put(MediaStore.MediaColumns.MIME_TYPE,data.first)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/$folder")
                })!!
                files+=uri; app.contentResolver.openOutputStream(uri)!!.use { it.write(data.second) }
            }
            ExtensionForm(installed=extension).use { form ->
                form.set("source_name",name); form.click("add_folder")
                waitFor { node { it.packageName?.toString()?.contains("documentsui")==true }!=null }
                click("Show roots")
                click(android.os.Build.MODEL)
                click("Download")
                click(folder)
                click("USE THIS FOLDER")
                click("Allow")
                form.await("source_name"); form.click("rename")
                waitFor { node { it.isEditable }!=null }
                check(node { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Reactions")
                }))
                click("OK"); form.click("save_library")
                withTimeout(12_000) { while(created==null) {
                    created=access.sources().current().profiles.firstOrNull { it.name==name }; delay(50)
                } }
            }
            val selected=created!!
            assertEquals(selected.id,access.sources().current().selectedId)
            val source=access.mediaSource()
            var results=MediaPage(emptyList())
            withTimeout(20_000) { while(results.items.isEmpty()) { results=source.page(MediaQuery("smile",mode=SearchMode.DESCRIPTION)); delay(100) } }
            assertTrue(results.items.any { it.title=="happy.png" })
            val capabilities=source.capabilities.first { it.modes.isNotEmpty() }
            val contextual=SearchMode.CONTEXT in capabilities.modes
            val query=if(contextual) MediaQuery("a smiling face") else MediaQuery(mode=SearchMode.FILENAME)
            results=source.page(query)
            withTimeout(60_000) { while(results.items.size!=4) { results=source.page(query); delay(100) } }
            if(contextual) assertEquals(SearchMode.CONTEXT,capabilities.defaultMode)
            for((file,data) in contents) {
                val item=results.items.single { it.title==file }
                assertArrayEquals(data.second,source.original(item).use { it.readBytes() })
                if(item.isVideo) assertArrayEquals(data.second,source.video(item)!!.readBytes())
            }
            ExtensionForm(selected,extension).use { form ->
                form.await("source_name") { it.text.toString()==name }
                pauseSourceVisual("local-release-progress")
                form.click("pause_folder"); form.await("pause_folder") { it.text.toString()=="Resume" }
                pauseSourceVisual("local-release-paused")
                form.click("pause_folder"); form.await("pause_folder") { it.text.toString()=="Pause" }
                form.click("restart_folder"); click("Restart")
                form.await("pause_folder") { it.isEnabled }
                withTimeout(60_000) { while(source.page(query).items.size!=4) delay(100) }
                assertEquals(4,source.page(query).items.size)
            }
        } finally {
            val profile=created ?: access.sources().current().profiles.firstOrNull { it.name==name }
            if(profile!=null) {
                val config=profile.configuration as ExtensionConfig
                ExtensionClient(app,extension!!.component).request(config.profile,ExtensionProtocol.DELETE_PROFILE,JSONObject().put("revision",config.revision))
                access.sources().remove(profile.id)
            }
            access.sources().restore(previous); access.browseSession().reset()
            files.forEach { app.contentResolver.delete(it,null,null) }
        }
    }
}
