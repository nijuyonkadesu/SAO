package one.njk.sao.floating

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** Explicit public model fixtures in shared storage; no user media or private server access. */
class LocalModelSetupDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun find(allWindows: Boolean=false,predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null) return null
            if(predicate(node)) return node
            repeat(node.childCount) { visit(node.getChild(it))?.let { value -> return value } }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow) ?: if(allWindows) instrumentation.uiAutomation.windows.firstNotNullOfOrNull { visit(it.root) } else null
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds=android.graphics.Rect(); node.getBoundsInScreen(bounds)
        val down=SystemClock.uptimeMillis()
        for(action in intArrayOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0).also { event ->
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(instrumentation.uiAutomation.injectInputEvent(event,true)); event.recycle()
            }
        }
        instrumentation.uiAutomation.waitForIdle(300,5000)
    }
    private fun click(text: String,popup: Boolean=false) {
        val until=SystemClock.elapsedRealtime()+12_000
        while(SystemClock.elapsedRealtime()<until) {
            fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                var target: AccessibilityNodeInfo?=node
                while(target!=null && !target.isClickable) target=target.parent
                return target
            }
            val node=find(popup) { (it.text?.toString()?.equals(text,true)==true || it.contentDescription?.toString()?.equals(text,true)==true) && (popup || clickable(it)!=null) }
            if(node!=null) {
                if(popup) { tap(node); return }
                if(clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { instrumentation.uiAutomation.waitForIdle(200,5000); return }
            }
            SystemClock.sleep(80)
        }
        val labels=mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) { if(node==null) return; if(node.text!=null || node.contentDescription!=null) labels+="${node.text} [${node.contentDescription}]"; repeat(node.childCount) { collect(node.getChild(it)) } }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        fail("Missing system control: $text; visible labels: $labels")
    }
    private fun model(form: ExtensionForm,label: String) {
        form.await("model_choice") { it.isEnabled }
        find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_choice" }!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(250)
        tap(find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_choice" }!!)
        click(label,popup=true)
    }
    private fun folder(form: ExtensionForm,name: String) {
        find { it.viewIdResourceName=="one.njk.sao.extension.local:id/choose_model_folder" }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        form.click("choose_model_folder")
        if(find { it.text?.toString()=="SAO-model-fixtures" }==null) {
            click("Show roots"); click(android.os.Build.MODEL); click("Documents")
        }
        click("SAO-model-fixtures"); click(name); click("USE THIS FOLDER"); click("Allow")
        val until=SystemClock.elapsedRealtime()+90_000
        while(SystemClock.elapsedRealtime()<until) {
            val text=find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_status" }?.text?.toString().orEmpty()
            if(text.contains("model ready",true)) return
            SystemClock.sleep(100)
        }
        fail("Model folder was not ready")
    }
    @Test fun chooseAndReuseExternalModelsAndMigrateOldDownloadWithoutNetwork()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("externalModelFixtures")=="true")
        val app=instrumentation.targetContext
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val extension=testExtensions(app).installed().single { it.component.packageName=="one.njk.sao.extension.local" }
        ExtensionForm(installed=extension).use { form ->
            folder(form,"Immich-default")
            assertTrue(find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_location" }!!.text.toString().contains("SAO-model-fixtures/Immich-default"))
            model(form,"CLIP 8-bit · 155 MB")
            folder(form,"CLIP-8bit")
            assertTrue(find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_location" }!!.text.toString().contains("SAO-model-fixtures/CLIP-8bit"))
            model(form,"Immich default · 609 MB")
            form.await("model_status") { it.text.toString().contains("model ready",true) }
            model(form,"CLIP 8-bit · 155 MB")
            form.await("context_search") { it.isChecked }
            find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_choice" }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            pauseSourceVisual("local-external-models")
        }
        ExtensionForm(installed=extension).use { form ->
            form.await("model_choice") { it.isEnabled }
            find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_choice" }!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            form.await("model_status") { it.text.toString().contains("model ready",true) }
            assertTrue(find { it.viewIdResourceName=="one.njk.sao.extension.local:id/model_location" }!!.text.toString().contains("Immich-default"))
        }
    }    @Test fun removedProfileCanBeConfiguredAgainAfterExtensionReinstall()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("externalModelFixtures")=="true")
        val app=instrumentation.targetContext
        val extension=testExtensions(app).installed().single { it.component.packageName=="one.njk.sao.extension.local" }
        val missing=extension.profile(one.njk.sao.source.ExtensionProfile(java.util.UUID.randomUUID().toString(),"Removed fixture",java.util.UUID.randomUUID().toString()))
        ExtensionForm(missing,extension).use { form ->
            form.await("source_name") { it.isEnabled }
            form.await("model_status") { !it.text.isNullOrBlank() }
            assertFalse(find { it.viewIdResourceName=="one.njk.sao.extension.local:id/error" }?.isVisibleToUser==true)
        }
    }

}
