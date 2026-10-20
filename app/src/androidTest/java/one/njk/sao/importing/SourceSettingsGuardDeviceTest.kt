package one.njk.sao.importing

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.runBlocking
import one.njk.sao.floating.*
import org.junit.Assert.*
import org.junit.Test

/** Real optimized extension Activities, including native/system Back and failed-save recovery. */
class SourceSettingsGuardDeviceTest {
    @Test fun immichWarnsOnlyForChangedDrafts()=check("immich","Immich")
    @Test fun localWarnsOnlyForChangedDrafts()=check("local","Local stickers")
    @Test fun moeviewWarnsOnlyForChangedDrafts()=check("moeview","Moeview")

    private fun check(kind: String,defaultName: String)=runBlocking {
        NativeImportFixtures().use { fixture ->
            val extension=testExtensions(fixture.app).installed().single { it.component.packageName=="one.njk.sao.extension.$kind" }
            val client=one.njk.sao.source.ExtensionClient(fixture.app,extension.component)
            val before=client.profiles()
            ExtensionForm(installed=extension).use { form ->
                assertEquals(defaultName,form.text("source_name"))
                form.set("source_name","Unsaved reactions")
                form.click("back")
                fixture.click("Keep editing")
                fixture.instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(200)
                assertEquals("Unsaved reactions",form.text("source_name"))
                fixture.instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if(kind=="local") {
                    // Incomplete source: saving from the warning must leave the draft available.
                    fixture.click("Save source")
                    form.await("error")
                    form.await("source_name") { it.text.toString()=="Unsaved reactions" }
                    form.click("back")
                }
                pauseSourceVisual("$kind-unsaved-source")
                fixture.click("Discard changes")
            }
            assertEquals("Discarding must not create a source",before,client.profiles())
            ExtensionForm(installed=extension).use { form ->
                form.set("source_name","Temporary edit")
                form.set("source_name",defaultName)
                // Reverting to the initial draft exits immediately, without a false warning.
                form.click("back")
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                while(android.os.SystemClock.elapsedRealtime()<deadline && fixture.instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("${extension.component.packageName}:id/source_name")?.isNotEmpty()==true)
                    android.os.SystemClock.sleep(50)
                assertTrue(fixture.instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("${extension.component.packageName}:id/source_name").isNullOrEmpty())
            }
        }
    }
}
