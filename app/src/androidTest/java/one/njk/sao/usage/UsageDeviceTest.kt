package one.njk.sao.usage

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import one.njk.sao.MainActivity
import one.njk.sao.R
import one.njk.sao.source.FloatingDispatchers
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class UsageDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun awaitReport(scenario: ActivityScenario<MainActivity>,page: UsagePage): Unit {
        val deadline=SystemClock.elapsedRealtime()+5000
        var ready=false
        do {
            scenario.onActivity { activity ->
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val fragment=host.childFragmentManager.primaryNavigationFragment as? UsageFragment ?: return@onActivity
                val model=androidx.lifecycle.ViewModelProvider(fragment)[UsageViewModel::class.java]
                ready=model.state.value.report?.selection==model.selectionState.value && model.selectionState.value.page==page &&
                    activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.usage_tabs)?.selectedTabPosition==page.ordinal &&
                    activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period)?.text?.isNotBlank()==true
            }
            if(!ready) SystemClock.sleep(40)
        } while(!ready && SystemClock.elapsedRealtime()<deadline)
        assertTrue("Expected the selected report to finish rendering: $page",ready)
    }
    @Test fun incrementsPauseAndClearAreOrderedAndDoNotReplayQueuedEvents(): Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(app,UsageDatabase::class.java).build()
        val repository=UsageRepository(db,FloatingDispatchers())
        try {
            repository.record(UsageEvent(UsageAction.SEARCH,UsageSurface.PICKER))
            repository.flush()
            assertTrue("Opening an unconfigured picker is not a backend search",repository.days.first().isEmpty())
            val event=UsageEvent(UsageAction.COPY_MEDIA,UsageSurface.PICKER,"f7a936dd-f5bc-4e99-a344-bd6a394f1788:secret-hash",UsageMedia.IMAGE)
            repeat(100) { repository.record(event) }; repository.flush()
            val selection=UsageSelection(UsagePage.DAY,LocalDate.now(),java.time.DayOfWeek.MONDAY)
            assertEquals(100L,repository.buckets(selection).first().single().occurrences)
            assertEquals("f7a936dd-f5bc-4e99-a344-bd6a394f1788",repository.buckets(selection).first().single().sourceId)
            repository.enabled(false); repeat(10) { repository.record(event) }; repository.flush()
            assertEquals(100L,repository.days.first().single().actions)
            repository.clear(); repository.flush()
            assertTrue(repository.days.first().isEmpty()); assertFalse(repository.enabled.first())
            repository.enabled(true); repository.record(event); repository.flush()
            assertEquals(1L,repository.days.first().single().actions)
            assertFalse(repository.failure.value)
        } finally { repository.close(); db.close() }
    }
    @Test fun usageDestinationHasOrderedEntryAndRetainsPageAndDateAcrossRecreation(): Unit=runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val menu=activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar).menu
                val ids=(0 until menu.size()).map { menu.getItem(it).itemId }
                assertTrue(ids.indexOf(R.id.edit)<ids.indexOf(R.id.usage))
                assertTrue(ids.indexOf(R.id.usage)<ids.indexOf(R.id.settings))
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                host.navController.navigate(R.id.usageFragment)
            }
            awaitReport(scenario,UsagePage.OVERVIEW)
            scenario.onActivity { activity ->
                activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.usage_tabs).getTabAt(2)!!.select()
            }
            awaitReport(scenario,UsagePage.WEEK)
            scenario.onActivity { it.findViewById<View>(R.id.usage_previous).performClick() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(250)
            var before=""
            scenario.onActivity { before=it.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString() }
            scenario.recreate(); awaitReport(scenario,UsagePage.WEEK)
            scenario.onActivity { activity ->
                assertEquals(2,activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.usage_tabs).selectedTabPosition)
                assertEquals(before,activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString())
                val root=activity.findViewById<View>(R.id.usage_content)
                assertTrue(root.width>0); assertTrue(root.height>0)
            }
        }
    }
    @Test fun seededReportsCanBeCapturedWithoutProductionSeedData(): Unit=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("captureUsage")!="true") return@runBlocking
        val access=EntryPointAccessors.fromApplication(app,UsageDeviceAccess::class.java)
        val repository=access.usage(); repository.clear(); repository.enabled(true)
        val today=LocalDate.now()
        for(offset in 0..55) {
            val date=today.minusDays(offset.toLong())
            if(offset%6==4) continue
            for(hour in listOf(9,13,18,21)) {
                access.usageDatabase().usage().record(UsageBucket(date.toEpochDay(),hour,UsageAction.SEARCH,UsageSurface.PICKER,
                    searchMode=UsageSearchMode.CONTEXT,occurrences=(offset%5+1).toLong(),results=40,latencyMillis=(offset%5+1)*180L))
                access.usageDatabase().usage().record(UsageBucket(date.toEpochDay(),hour,UsageAction.COPY_MEDIA,UsageSurface.PICKER,
                    media=if(offset%3==0) UsageMedia.VIDEO else UsageMedia.ANIMATION,occurrences=(offset%3+1).toLong()))
                access.usageDatabase().usage().record(UsageBucket(date.toEpochDay(),hour,UsageAction.PASTE,UsageSurface.PICKER,
                    media=UsageMedia.IMAGE,target=UsageTarget.entries[1+offset%4],route=UsageRoute.NATIVE,occurrences=2))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                host.navController.navigate(R.id.usageFragment)
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(350)
            for(index in 0..3) {
                scenario.onActivity { it.findViewById<com.google.android.material.tabs.TabLayout>(R.id.usage_tabs).getTabAt(index)!!.select() }
                awaitReport(scenario,UsagePage.entries[index]); instrumentation.waitForIdleSync(); SystemClock.sleep(200)
                val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(app.filesDir,"usage-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                scenario.onActivity { activity ->
                    val content=activity.findViewById<android.widget.LinearLayout>(R.id.usage_content)
                    val target=content.getChildAt(when(index) { 0 -> 2; 1,2 -> 1; else -> content.childCount-1 })
                    activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,target.top+content.top)
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    fun checkCalendars(view: View) {
                        if(view is UsageMonthView && view.width>0) {
                            val child=view.getChildAt(0)
                            assertEquals("Calendar must use the viewport or its accessible minimum width",maxOf(view.width,child.minimumWidth),child.width)
                        }
                        if(view is android.view.ViewGroup) for(child in 0 until view.childCount) checkCalendars(view.getChildAt(child))
                    }
                    checkCalendars(activity.findViewById(R.id.usage_content))
                }
                val detail=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(app.filesDir,"usage-$index-detail.png").outputStream().use { detail.compress(Bitmap.CompressFormat.PNG,100,it) }; detail.recycle()
            }
            val clipboard=EntryPointAccessors.fromApplication(app,one.njk.sao.floating.FloatingTestAccess::class.java).clipboard()
            val request=one.njk.sao.floating.ClipboardRequest()
            assertTrue(clipboard.publishFilename(one.njk.sao.source.MediaItem("usage-fixture","Fixture","","image/png",emptyList(),emptyList(),filename="never-store-this-filename.png"),request,request.next()))
            repository.flush()
            assertTrue(access.usageDatabase().usage().observeDays().first().any { it.day==today.toEpochDay() })
            assertTrue(access.usageDatabase().usage().observe(today.toEpochDay(),today.plusDays(1).toEpochDay()).first().any { it.action==UsageAction.COPY_FILENAME })
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.usage_recording).performClick() }
            kotlinx.coroutines.withTimeout(5000) { repository.enabled.first { !it } }
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.usage_clear).performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val button=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                    it.findViewById<View>(android.R.id.button1)
                }
                assertNotNull("The clear action requires confirmation",button)
                button!!.performClick()
            }
            kotlinx.coroutines.withTimeout(5000) { repository.days.first { it.isEmpty() } }
            assertFalse("Clearing does not silently resume recording",repository.enabled.first())
        }
    }
}
