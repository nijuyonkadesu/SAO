package one.njk.sao.floating

import android.content.ContentValues
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.util.UUID

/** Real platform picker grants; synthetic files only, including the optimized extension APK. */
class LocalPickedSetupDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if(node==null) return; result+=node; repeat(node.childCount) { visit(node.getChild(it)) } }
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val windows=automation.windows
        if(windows.isEmpty()) visit(automation.rootInActiveWindow) else windows.forEach { visit(it.root) }
        return result
    }
    private fun waitFor(label: String,predicate: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+15_000
        while(!predicate() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(80)
        assertTrue(label,predicate())
    }
    private fun click(label: String,long: Boolean=false) {
        val action=if(long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        fun candidate(): AccessibilityNodeInfo? {
            var node=nodes().firstOrNull { it.text?.toString()?.equals(label,true)==true || it.contentDescription?.toString()?.equals(label,true)==true }
            while(node!=null && if(long) !node.isLongClickable else !node.isClickable) node=node.parent
            return node
        }
        waitFor("Missing picker control: $label") { candidate()!=null }
        check(candidate()!!.performAction(action))
    }
    @Test fun photoPickerGrantsSelectedMediaAndReopensTheNamedCollection()=runBlocking {
        val access=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
        val extension=testExtensions(app).installed().firstOrNull { it.component.packageName=="one.njk.sao.extension.local" }
        Assume.assumeNotNull(extension)
        val key=UUID.randomUUID().toString().take(8); val album="SAO-picked-$key"; val name="Picked $key"
        val previous=access.sources().current(); val files=mutableListOf<Uri>(); var created: SourceProfile?=null
        val contents=linkedMapOf(
            "happy-$key.png" to ("image/png" to instrumentation.context.assets.open("floating/happy.png").use { it.readBytes() }),
            "hello-$key.gif" to ("image/gif" to instrumentation.context.assets.open("floating/hello.gif").use { it.readBytes() }),
            "clip-$key.mp4" to ("video/mp4" to instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }),
            "clip-$key.webm" to ("video/webm" to instrumentation.context.assets.open("preview-video.webm").use { it.readBytes() }))
        var previousMethod="Folder picker"
        try {
            contents.forEach { (filename,data) ->
                val collection=if(data.first.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri=app.contentResolver.insert(collection,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,filename); put(MediaStore.MediaColumns.MIME_TYPE,data.first)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/$album"); put(MediaStore.MediaColumns.IS_PENDING,1)
                    put(MediaStore.Images.ImageColumns.DATE_TAKEN,System.currentTimeMillis())
                })!!
                files+=uri; app.contentResolver.openOutputStream(uri)!!.use { it.write(data.second) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            }
            ExtensionForm(installed=extension).use { form ->
                form.set("source_name",name); previousMethod=form.text("import_method")
                form.click("import_method"); click("Photo picker"); form.click("add_folder")
                waitFor("Photo picker did not open") { nodes().any { it.packageName?.toString()?.contains("providers.media")==true } }
                pauseSourceVisual("local-photo-picker")
                fun cells()=nodes().filter { it.viewIdResourceName?.endsWith("/icon_thumbnail")==true }.sortedWith(compareBy({ Rect().also(it::getBoundsInScreen).top },{ Rect().also(it::getBoundsInScreen).left }))
                waitFor("Four synthetic picker thumbnails") { cells().size>=4 }
                repeat(4) { index ->
                    var node: AccessibilityNodeInfo?=cells()[index]
                    while(node!=null && !node.isClickable) node=node.parent
                    check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
                    SystemClock.sleep(80)
                }
                val add=nodes().firstOrNull { it.text?.toString()?.startsWith("Add")==true && it.isClickable }
                check(add?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { "Photo picker Add action unavailable" }
                form.await("rename") { it.isEnabled }; form.click("rename")
                fun renameInput()=nodes().firstOrNull { it.isEditable && it.viewIdResourceName?.substringAfterLast('/') !in setOf("source_name","import_method","model_choice") }
                waitFor("Rename dialog input") { renameInput()!=null }
                val edit=renameInput()!!
                check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Chosen reactions") }))
                click("OK"); form.click("save_library")
                try { withTimeout(15_000) { while(created==null) { created=access.sources().current().profiles.firstOrNull { it.name==name }; delay(50) } } }
                catch(error: TimeoutCancellationException) { throw AssertionError("Save did not return profile $name; fields="+nodes().filter { it.viewIdResourceName?.substringAfterLast('/') in setOf("error","source_name") }.map { "${it.viewIdResourceName}=${it.text}" }+"; profiles="+access.sources().current(),error) }
            }
            val source=access.mediaSource()
            var results=MediaPage(emptyList())
            withTimeout(60_000) { while(results.items.size!=4) { results=source.page(MediaQuery(mode=SearchMode.FILENAME)); delay(100) } }
            val expected=contents.values.associate { it.first to it.second }
            assertEquals(expected.keys,results.items.map { it.mimeType }.toSet())
            // The photo picker may deliberately expose provider IDs instead of original filenames.
            results.items.forEach { assertArrayEquals(expected.getValue(it.mimeType),source.original(it).use { stream -> stream.readBytes() }) }
            ExtensionForm(created,extension).use { form ->
                form.await("name") { it.text.toString()=="Chosen reactions" }
                form.await("add_media") { it.isEnabled }; pauseSourceVisual("local-picked-collection")
                form.click("import_method"); click("Files picker"); form.click("add_media")
                waitFor("Files picker did not open") { nodes().any { it.packageName?.toString()?.contains("documentsui")==true } }
                pauseSourceVisual("local-files-picker")
                // DocumentsUI may first navigate to a parent folder. Back out until its activity closes.
                for(attempt in 0 until 8) {
                    if(nodes().none { it.packageName?.toString()?.contains("documentsui")==true }) break
                    instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    SystemClock.sleep(250)
                }
                form.await("source_name") { it.isEnabled }
                form.click("import_method"); click(previousMethod)
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
