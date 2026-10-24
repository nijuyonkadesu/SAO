package one.njk.sao.floating

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import one.njk.sao.R
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.settings.SourceChipChoices
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual native chip states and authenticated cross-APK settings. No real accounts or API requests. */
class SourceChipDesignDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun await(label: String,predicate: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+12_000
        while(!predicate() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(40)
        assertTrue(label,predicate())
    }
    private fun capture(name: String) {
        if(InstrumentationRegistry.getArguments().getString("captureChips")!="true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(450)
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir,"chip-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
    }
    @Test fun modelRenderingDoesNotTriggerChoiceWritesAndSelectionUsesThemeColors() {
        var selections=0
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
            scenario.onActivity { activity ->
                val adapter=SourceListAdapter({}, { _,_-> }, {})
                val parent=android.widget.FrameLayout(activity)
                val holder=adapter.onCreateViewHolder(parent,0)
                activity.setContentView(parent); parent.addView(holder.binding.root)
                val group=holder.binding.useSource.parent as com.google.android.material.chip.ChipGroup
                val choices=SourceChipChoices(group,listOf("first" to holder.binding.useSource,"second" to holder.binding.floatingEnabled)) { selections++ }
                holder.binding.floatingEnabled.visibility=android.view.View.VISIBLE
                choices.render("first")
                assertEquals(0,selections); assertTrue(holder.binding.useSource.isChecked); assertFalse(holder.binding.floatingEnabled.isChecked)
                val chip=holder.binding.useSource
                assertNotNull(chip.chipIcon)
                assertEquals(MaterialColors.getColor(chip,com.google.android.material.R.attr.colorSecondaryContainer),
                    chip.chipBackgroundColor!!.getColorForState(chip.drawableState,Color.MAGENTA))
                holder.binding.floatingEnabled.performClick(); assertEquals(1,selections)
                choices.render("first",false); assertEquals(1,selections)
                assertFalse(chip.isEnabled); assertFalse(holder.binding.floatingEnabled.isEnabled)
            }
        }
    }
    @Test fun homeSelectionAndFloatingVisibilityRemainIndependent(): Unit=runBlocking {
        val profiles=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java).sources()
        val previous=profiles.snapshots.first()
        val first=immichProfile("Home reactions",ImmichConfig("https://first.example.invalid","synthetic-key"))
        val second=immichProfile("Other reactions",ImmichConfig("https://second.example.invalid","synthetic-key"))
        try {
            profiles.upsert(first,true); profiles.upsert(second); profiles.selectFloating(second.id)
            ActivityScenario.launch<SourceSettingsActivity>(android.content.Intent(app,SourceSettingsActivity::class.java)).use { scenario ->
                var ready=false
                await("Profile chip did not render") {
                    scenario.onActivity { activity ->
                        val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.source_list)
                        ready=(0 until list.childCount).mapNotNull { list.getChildViewHolder(list.getChildAt(it)) as? SourceListAdapter.Holder }
                            .any { it.binding.name.text.toString()==first.name }
                    }; ready
                }
                scenario.onActivity { activity ->
                    val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.source_list)
                    val holder=(0 until list.childCount).mapNotNull { list.getChildViewHolder(list.getChildAt(it)) as? SourceListAdapter.Holder }
                        .first { it.binding.name.text.toString()==first.name }
                    assertTrue(holder.binding.useSource.isChecked); assertTrue(holder.binding.floatingEnabled.isChecked)
                    assertNotNull(holder.binding.edit.chipIcon)
                    holder.binding.floatingEnabled.performClick()
                }
                await("Floating chip did not persist") { runBlocking { first.id in profiles.snapshots.first().floatingDisabled } }
                assertEquals(first.id,profiles.current().selectedId)
                assertEquals(second.id,profiles.snapshots.first().floatingSelectedId)
                capture("manage")
            }
        } finally { profiles.restore(previous) }
    }
    private fun node(pkg: String,id: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull()
    private fun show(pkg: String,id: String) {
        await("Missing $pkg:$id") { node(pkg,id)!=null }
        node(pkg,id)!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node(pkg,id)!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        await("Hidden $pkg:$id") { node(pkg,id)?.isVisibleToUser==true }
        instrumentation.waitForIdleSync()
    }
    @Test fun everyExtensionExposesCompactModelOwnedChoices()=runBlocking {
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val extensions=testExtensions(app).installed()
        for(kind in listOf("immich","local","moeview","findthatmeme","googlephotos")) {
            val extension=extensions.single { it.component.packageName=="one.njk.sao.extension.$kind" }
            val pkg=extension.component.packageName
            ExtensionForm(installed=extension).use { form ->
                form.await("source_name") { it.isEnabled }
                when(kind) {
                    "googlephotos" -> {
                        show(pkg,"all_media"); assertTrue(node(pkg,"all_media")!!.isChecked)
                        assertEquals("All media",node(pkg,"all_media")!!.text.toString())
                        form.click("all_media"); assertTrue(node(pkg,"all_media")!!.isChecked)
                        assertEquals("Choose album",node(pkg,"choose_album")!!.text.toString())
                        capture(kind)
                    }
                    "immich" -> {
                        show(pkg,"all_media"); assertTrue(node(pkg,"all_media")!!.isChecked)
                        capture(kind)
                    }
                    "local" -> {
                        show(pkg,"nsfw"); form.click("nsfw"); assertTrue(node(pkg,"nsfw")!!.isChecked)
                        show(pkg,"picker_photos"); form.click("picker_photos"); assertTrue(node(pkg,"picker_photos")!!.isChecked)
                        assertFalse(node(pkg,"picker_folder")!!.isChecked)
                        capture("local-collections")
                        show(pkg,"model_lite"); form.click("model_lite"); assertTrue(node(pkg,"model_lite")!!.isChecked)
                        assertFalse(node(pkg,"model_immich")!!.isChecked)
                        capture("local-model")
                    }
                    "moeview" -> {
                        show(pkg,"board_danbooru"); form.click("board_danbooru"); assertTrue(node(pkg,"board_danbooru")!!.isChecked)
                        show(pkg,"rating_all"); form.click("rating_all"); assertTrue(node(pkg,"rating_all")!!.isChecked)
                        assertFalse(node(pkg,"rating_safe")!!.isChecked)
                        capture(kind)
                    }
                    "findthatmeme" -> {
                        show(pkg,"include_sensitive"); form.click("include_sensitive"); assertTrue(node(pkg,"include_sensitive")!!.isChecked)
                        capture(kind)
                    }
                }
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                if(kind in listOf("local","moeview","findthatmeme")) {
                    await("Unsaved changes dialog missing") { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Discard changes")?.isNotEmpty()==true }
                    automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Discard changes").first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
    }
}
