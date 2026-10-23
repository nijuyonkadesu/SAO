package one.njk.sao.floating

import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Run after the local extension's explicit prepareHostFixture test; no private library reads. */
class LocalSourceDeviceTest {
    @get:org.junit.Rule val marquees=PickerMarqueeRule()
    @Test fun installedLocalLibrarySearchesAndExportsAllFormatsInTheOriginalPicker()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val app=instrumentation.targetContext
        val access=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
        val extensions=testExtensions(app)
        val extension=extensions.installed().firstOrNull { it.component.packageName=="one.njk.sao.extension.local" }
        Assume.assumeNotNull(extension)
        val rows=ExtensionClient(app,extension!!.component).profiles()
        val fixture=rows.firstOrNull { it.id=="de89d2d4-3110-4fb4-894b-5cef5e304e70" }
        Assume.assumeNotNull(fixture)
        val previous=access.sources().snapshots.first()
        val profile=extension.profile(fixture!!)
        try {
            access.sources().upsert(profile,true); access.sources().selectFloating(profile.id); access.browseSession().reset()
            val source=access.mediaSource()
            val all=source.page(MediaQuery()).items
            assertTrue(all.any { it.mimeType=="image/png" }); assertTrue(all.any { it.mimeType=="image/gif" })
            for(mime in listOf("image/png","image/gif","video/mp4","video/webm")) {
                val item=all.first { it.mimeType==mime }
                val bytes=source.withRendition(item,Rendition.ORIGINAL) { handle -> handle.openStream().use { it.readBytes() } }
                assertTrue(bytes.isNotEmpty())
                if(item.isVideo) assertArrayEquals(bytes,
                    source.withRendition(item,Rendition.PLAYBACK) { handle -> handle.requireFile().readBytes() })
                else assertTrue((source.preview(item) as Preview.Local).file.isFile)
            }
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
                lateinit var picker: PickerViewModel
                scenario.onActivity { picker=ViewModelProvider(it)[PickerViewModel::class.java] }
                withTimeout(12_000) { while(picker.state.value.items.isEmpty() || picker.query.value.mode!=SearchMode.DESCRIPTION) delay(30) }
                assertFalse(SearchMode.CONTEXT in source.capabilities.first { it.sourceId != null }.modes)
                scenario.onActivity { it.findViewById<View>(one.njk.sao.R.id.search_toggle).performClick() }
                pauseSourceVisual("local-picker")
                scenario.onActivity { picker.searchMode(SearchMode.FILENAME); picker.search("preview-video.mp4") }
                withTimeout(8000) { while(picker.state.value.loading || picker.state.value.query.text!="preview-video.mp4") delay(25) }
                assertTrue(picker.state.value.items.isNotEmpty()); assertTrue(picker.state.value.items.all { it.mimeType=="video/mp4" })
            }
            ExtensionForm(profile,extension).use { form ->
                form.await("source_name") { it.text.toString()=="Local test library" }
                pauseSourceVisual("local-settings")
                form.click("browse_metadata"); form.await("items") { it.childCount>0 }
                pauseSourceVisual("local-metadata")
                form.clickFirst("items"); form.await("tags")
                pauseSourceVisual("local-edit")
                form.click("back"); form.await("items"); form.click("back"); form.await("source_name")
            }
        } finally { access.sources().restore(previous); access.browseSession().reset() }
    }
}
