package one.njk.sao.floating

import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import one.njk.sao.R
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.util.UUID

class SourcePickerUxDeviceTest {
    @get:org.junit.Rule val marquees=PickerMarqueeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val access get()=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
    private suspend fun await(predicate: ()->Boolean) { withTimeout(12_000) { while(!predicate()) delay(30) } }
    @Test fun emptyPickerExplainsHowToAddSourcesAndHidesUninstalledProfiles()=runBlocking {
        val previous=access.sources().current()
        val providers=testExtensions(app).installed()
        fun shell(command: String)=instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val enabled=providers.map { it.component.packageName }.distinct().associateWith { app.packageManager.getApplicationEnabledSetting(it) }
        try {
            enabled.keys.forEach { shell("pm disable-user --user 0 $it") }
            access.sources().restore(SourceCatalog())
            access.sources().upsert(SourceProfile(UUID.randomUUID().toString(),"Removed extension",ExtensionConfig("missing.source","missing.source.Service","fixture","v1","Missing")),select=true)
            access.browseSession().reset()
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
                var diagnostic=""
                try { await { var shown=false; scenario.onActivity {
                    val sources=ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value
                    diagnostic="sources=$sources; empty=${it.findViewById<View>(R.id.no_source).visibility}; sourcePanel=${it.findViewById<View>(R.id.source_panel).visibility}"
                    shown=it.findViewById<View>(R.id.no_source).visibility==View.VISIBLE
                }; shown } } catch(error: TimeoutCancellationException) { throw AssertionError(diagnostic,error) }
                pauseSourceVisual("picker-no-sources")
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.add_first_source).visibility)
                    assertEquals(View.GONE,activity.findViewById<View>(R.id.filter_row).visibility)
                    val model=ViewModelProvider(activity)[PickerViewModel::class.java]
                    assertTrue(model.state.value.items.isEmpty())
                    activity.findViewById<View>(R.id.source_toggle).performClick()
                }
                try { await { var empty=false; scenario.onActivity {
                    val list=it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.source_list)
                    diagnostic="list count=${list.adapter!!.itemCount}; sources=${ViewModelProvider(it)[SourceProfilesViewModel::class.java].state.value}"
                    empty=list.adapter!!.itemCount==0
                }; empty } } catch(error: TimeoutCancellationException) { throw AssertionError(diagnostic,error) }
            }
        } finally {
            enabled.forEach { (packageName,state) -> shell("pm ${if(state==android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) "default-state" else "enable"} --user 0 $packageName") }
            access.sources().restore(previous); access.browseSession().reset()
        }
    }
    @Test fun customEmojiRowPreservesSequencesSearchesAsTextAndCanBeDisabled()=runBlocking {
        assertEquals(listOf("👩🏽‍💻","🇯🇵","❤️","1️⃣"),EmojiShortcuts.parse("👩🏽‍💻, 🇯🇵 ❤️ 👩🏽‍💻 1️⃣ words"))
        val extension=testExtensions(app).installed().firstOrNull { it.component.packageName=="one.njk.sao.extension.local" }
        Assume.assumeNotNull(extension)
        val fixture=ExtensionClient(app,extension!!.component).profiles().firstOrNull { it.name=="Local test library" }
        Assume.assumeNotNull(fixture)
        val previous=access.sources().current(); val options=access.preferences().current()
        try {
            access.sources().upsert(extension.profile(fixture!!),select=true); access.browseSession().reset()
            access.preferences().emojiShortcuts("😊 👩🏽‍💻 🇯🇵 ❤️ 😊")
            access.preferences().showEmojiShortcuts(true)
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,null,null)).use { scenario ->
                await { var ready=false; scenario.onActivity { ready=it.findViewById<ChipGroup>(R.id.filters).childCount==4 && it.findViewById<View>(R.id.filter_row).visibility==View.VISIBLE }; ready }
                pauseSourceVisual("picker-custom-emoji")
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[PickerViewModel::class.java].filter("old metadata filter")
                    (activity.findViewById<ChipGroup>(R.id.filters).getChildAt(1) as Chip).performClick()
                    val query=ViewModelProvider(activity)[PickerViewModel::class.java].query.value
                    assertEquals("👩🏽‍💻",query.text); assertNull(query.filter)
                }
                access.preferences().showEmojiShortcuts(false)
                await { var hidden=false; scenario.onActivity { hidden=it.findViewById<View>(R.id.filter_row).visibility==View.GONE }; hidden }
            }
        } finally {
            access.preferences().showEmojiShortcuts(options.showEmojiShortcuts); access.preferences().emojiShortcuts(options.emojiShortcuts)
            access.sources().restore(previous); access.browseSession().reset()
        }
    }
    @Test fun settingsCanToggleAndEditTheShortcutList()=runBlocking {
        val previous=access.preferences().current()
        try {
            access.preferences().showEmojiShortcuts(false)
            ActivityScenario.launch<one.njk.sao.SettingsActivity>(one.njk.sao.SettingsActivity::class.java).use { scenario ->
                await { var ready=false; scenario.onActivity { ready=it.findViewById<View>(R.id.emoji_shortcuts)!=null }; ready }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.emoji_shortcuts))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click())
                withTimeout(5000) { while(!access.preferences().current().showEmojiShortcuts) delay(30) }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.edit_emoji_shortcuts))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.google.android.material.textfield.TextInputEditText::class.java))
                    .perform(androidx.test.espresso.action.ViewActions.replaceText("😊 👩🏽‍💻 🇯🇵 😊"))
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.ok)).perform(androidx.test.espresso.action.ViewActions.click())
                withTimeout(5000) { while(access.preferences().current().emojiShortcuts!="😊 👩🏽‍💻 🇯🇵") delay(30) }
                pauseSourceVisual("emoji-shortcut-settings")
            }
        } finally {
            access.preferences().emojiShortcuts(previous.emojiShortcuts); access.preferences().showEmojiShortcuts(previous.showEmojiShortcuts)
        }
    }

}
