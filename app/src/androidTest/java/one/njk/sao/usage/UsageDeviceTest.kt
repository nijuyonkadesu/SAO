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
    private fun awaitReport(scenario: ActivityScenario<MainActivity>,date: LocalDate = LocalDate.now()): Unit {
        val deadline=SystemClock.elapsedRealtime()+5000
        var ready=false
        do {
            scenario.onActivity { activity ->
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val fragment=host.childFragmentManager.primaryNavigationFragment as? UsageFragment ?: return@onActivity
                val model=androidx.lifecycle.ViewModelProvider(fragment)[UsageViewModel::class.java]
                ready=model.state.value.report?.selection==model.selectionState.value && model.selectionState.value.anchor==date &&
                    activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period)?.text?.isNotBlank()==true
            }
            if(!ready) SystemClock.sleep(40)
        } while(!ready && SystemClock.elapsedRealtime()<deadline)
        assertTrue("Expected the selected report to finish rendering: $date",ready)
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
            val selection=UsageSelection(UsageRange.DAY,LocalDate.now(),java.time.DayOfWeek.MONDAY)
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
    @Test fun usageDestinationHasOrderedEntryAndRetainsDateAndScrollAcrossRecreation(): Unit=runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val menu=activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar).menu
                val ids=(0 until menu.size()).map { menu.getItem(it).itemId }
                assertTrue(ids.indexOf(R.id.edit)<ids.indexOf(R.id.usage))
                assertTrue(ids.indexOf(R.id.usage)<ids.indexOf(R.id.settings))
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                host.navController.navigate(R.id.usageFragment)
            }
            awaitReport(scenario)
            scenario.onActivity { it.findViewById<View>(R.id.usage_previous).performClick() }
            awaitReport(scenario,LocalDate.now().minusDays(1))
            var daily: View?=null
            scenario.onActivity { activity ->
                daily=activity.findViewById(R.id.usage_daily_chart)
                activity.findViewById<View>(R.id.usage_calendar_month).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertSame("Changing calendar mode keeps the other reports intact",daily,activity.findViewById(R.id.usage_daily_chart))
                assertEquals(UsageRange.MONTH,activity.findViewById<UsageHeatmapView>(R.id.usage_heatmap).period.range)
            }
            var before=""
            var scroll=0
            scenario.onActivity { activity ->
                before=activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString()
                activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,700)
                scroll=activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollY
            }
            scenario.recreate(); awaitReport(scenario,LocalDate.now().minusDays(1))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(before,activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString())
                assertEquals(scroll,activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollY)
                assertTrue(activity.findViewById<com.google.android.material.chip.Chip>(R.id.usage_calendar_month).isChecked)
                val ids=listOf(R.id.usage_activity_calendar,R.id.usage_daily_chart,R.id.usage_weekly_chart,R.id.usage_destinations,R.id.usage_breakdown,R.id.usage_records)
                val tops=ids.map { (activity.findViewById<View>(it).parent as View).top }
                assertEquals("Reports appear together in one scrolling page",tops.sorted(),tops)
            }
            scenario.onActivity { it.findViewById<View>(R.id.usage_calendar_previous).performClick() }
            awaitReport(scenario,LocalDate.now().minusDays(1).minusMonths(1))
            scenario.onActivity { it.findViewById<View>(R.id.usage_calendar_next).performClick() }
            awaitReport(scenario,LocalDate.now().minusDays(1))
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
            awaitReport(scenario)
            val sections=listOf(0,R.id.usage_activity_calendar,R.id.usage_daily_chart,R.id.usage_weekly_chart,R.id.usage_destinations,R.id.usage_breakdown,R.id.usage_records)
            sections.forEachIndexed { index,id ->
                scenario.onActivity { activity ->
                    val content=activity.findViewById<android.widget.LinearLayout>(R.id.usage_content)
                    val y=if(id==0) 0 else (activity.findViewById<View>(id).parent as View).top+content.top
                    activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,y)
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(180)
                val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(app.filesDir,"usage-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            scenario.onActivity { activity ->
                val chart=activity.findViewById<UsageHeatmapView>(R.id.usage_heatmap)
                val rect=chart.bounds(today)
                assertTrue("Cells are compact chart entries",rect.width()<24*app.resources.displayMetrics.density)
                val scroll=chart.parent as android.widget.HorizontalScrollView
                val provider=chart.accessibilityNodeProvider!!
                val date=today.minusDays(2)
                val node=provider.createAccessibilityNodeInfo(date.dayOfYear-1)!!
                assertTrue(node.contentDescription.isNotBlank()); assertTrue(node.isClickable); assertFalse(node.isSelected)
                assertTrue(provider.performAction(date.dayOfYear-1,androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK,null))
                assertTrue(scroll.width>0)
            }
            awaitReport(scenario,today.minusDays(2))
            scenario.onActivity { it.findViewById<View>(R.id.usage_calendar_month).performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val chart=activity.findViewById<UsageHeatmapView>(R.id.usage_heatmap)
                assertEquals(UsageRange.MONTH,chart.period.range)
                assertTrue("All seven weekday columns fit the monthly viewport",chart.width<=(chart.parent as View).width)
                val node=chart.accessibilityNodeProvider!!.createAccessibilityNodeInfo(today.minusDays(2).dayOfMonth-1)!!
                assertTrue(node.isSelected); assertTrue(node.isClickable)
                val card=activity.findViewById<View>(R.id.usage_activity_calendar).parent as View
                activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,card.top+activity.findViewById<View>(R.id.usage_content).top)
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(180)
            val month=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(app.filesDir,"usage-month.png").outputStream().use { month.compress(Bitmap.CompressFormat.PNG,100,it) }; month.recycle()
            assertTrue(access.usageDatabase().usage().observeDays().first().isNotEmpty())
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
