package one.njk.sao.importing

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.util.UUID

internal class NativeImportFixtures: AutoCloseable {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val app=instrumentation.targetContext
    val folder="SAO-import-test-${UUID.randomUUID()}"
    val files=mutableListOf<Uri>()
    fun image(name: String,color: Int): Pair<Uri,ByteArray> {
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); bitmap.recycle(); it.toByteArray() }
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name); put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/$folder")
        })!!
        files+=uri; app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri to bytes
    }
    private fun node(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(root==null) return null
            if(predicate(root)) return root
            repeat(root.childCount) { find(root.getChild(it))?.let { found -> return found } }
            return null
        }
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        return find(automation.rootInActiveWindow) ?: automation.windows.firstNotNullOfOrNull { find(it.root) }
    }
    private fun clickable(label: String): AccessibilityNodeInfo? {
        fun ancestor(value: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo?=value
            while(current!=null && !current.isClickable) current=current.parent
            return current
        }
        return node { (it.text?.toString()?.equals(label,true)==true || it.contentDescription?.toString()?.equals(label,true)==true) && ancestor(it)!=null }?.let(::ancestor)
    }
    fun click(label: String) {
        val deadline=SystemClock.elapsedRealtime()+12_000
        while(clickable(label)==null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
        assertTrue("Missing control $label",clickable(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    fun chooseFolder() {
        val deadline=SystemClock.elapsedRealtime()+12_000
        while(node { it.packageName?.toString()?.contains("documentsui")==true }==null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
        // Android can omit the roots drawer when this user has only one eligible storage root.
        if(clickable("Show roots")!=null) { click("Show roots"); click(android.os.Build.MODEL) }
        else if(clickable("Download")==null && clickable(android.os.Build.MODEL)!=null) click(android.os.Build.MODEL)
        click("Download"); click(folder)
        click("USE THIS FOLDER"); click("Allow")
    }
    override fun close() {
        files.forEach { runCatching { app.contentResolver.delete(it,null,null) } }
        // Only the UUID directory created by this test, including copies owned by the other APK.
        require(folder.matches(Regex("SAO-import-test-[a-f0-9-]{36}")))
        val root=android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
        require(root.matches(Regex("/storage/emulated/[0-9]+/Download")))
        instrumentation.uiAutomation.executeShellCommand("rm -rf $root/$folder").close()
    }
}
