package one.njk.sao.usage

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
import java.time.YearMonth

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
                val model=androidx.lifecycle.ViewModelProvider(activity)[UsageViewModel::class.java]
                ready=model.state.value.report?.selection==model.selectionState.value && model.selectionState.value.anchor==date &&
                    activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period)?.text?.isNotBlank()==true
            }
            if(!ready) SystemClock.sleep(40)
        } while(!ready && SystemClock.elapsedRealtime()<deadline)
        assertTrue("Expected the selected report to finish rendering: $date",ready)
    }
    private fun scroll(scenario: ActivityScenario<MainActivity>,kind: UsageSections.Kind) {
        scenario.onActivity { activity ->
            val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_scroll)
            val adapter=list.adapter as UsageSections
            (list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(adapter.position(kind),0)
        }
        instrumentation.waitForIdleSync()
    }
    private fun monthChart(activity: MainActivity): UsageHeatmapView? {
        val carousel=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_month_carousel) ?: return null
        val root=com.google.android.material.carousel.CarouselSnapHelper(false).findSnapView(carousel.layoutManager) ?: return null
        fun chart(view: View): UsageHeatmapView? {
            if(view is UsageHeatmapView) return view
            if(view is android.view.ViewGroup) for(index in 0 until view.childCount) chart(view.getChildAt(index))?.let { return it }
            return null
        }
        return chart(root)
    }
    private fun awaitMonth(scenario: ActivityScenario<MainActivity>,expected: YearMonth?=null): YearMonth {
        val deadline=SystemClock.elapsedRealtime()+8000
        var actual: YearMonth?=null
        do {
            scenario.onActivity { activity ->
                val carousel=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_month_carousel) ?: return@onActivity
                if(carousel.scrollState!=androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE || carousel.isLayoutRequested || carousel.isComputingLayout || !carousel.isLaidOut) return@onActivity
                val manager=carousel.layoutManager ?: return@onActivity
                val snap=com.google.android.material.carousel.CarouselSnapHelper(false)
                val focused=snap.findSnapView(manager) ?: return@onActivity
                if(snap.calculateDistanceToFinalSnap(manager,focused)?.any { kotlin.math.abs(it)>1 }==true) return@onActivity
                val chart=monthChart(activity) ?: return@onActivity
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val fragment=host.childFragmentManager.primaryNavigationFragment!!
                val model=androidx.lifecycle.ViewModelProvider(activity)[UsageViewModel::class.java]
                val month=YearMonth.from(chart.period.anchor)
                if(month==model.calendarMonth.value && (expected==null || expected==month) && chart.isShown && chart.width>0) actual=month
            }
            if(actual==null) SystemClock.sleep(40)
        } while(actual==null && SystemClock.elapsedRealtime()<deadline)
        assertNotNull("The carousel must settle on its displayed month: $expected",actual)
        return actual!!
    }
    private fun scrollMonth(scenario: ActivityScenario<MainActivity>,previous: Boolean) {
        scenario.onActivity { activity ->
            val carousel=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_month_carousel)
            val action=if(previous) AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
            assertTrue("Months remain browsable with accessibility actions",carousel.performAccessibilityAction(action,null))
        }
    }
    private fun toggleCalendar(scenario: ActivityScenario<MainActivity>) {
        scroll(scenario,UsageSections.Kind.CALENDAR)
        scenario.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.usage_calendar_scale)
            .performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,null)) }
    }
    private inner class ScaleTouch(scenario: ActivityScenario<MainActivity>) {
        private lateinit var activity: MainActivity
        private val down=SystemClock.uptimeMillis()
        var x=0f; var y=0f
        init {
            scenario.onActivity {
                activity=it
                val button=it.findViewById<View>(R.id.usage_calendar_scale)
                val location=IntArray(2).also(button::getLocationInWindow)
                x=location[0]+button.width/2f; y=location[1]+button.height/2f
            }
            send(MotionEvent.ACTION_DOWN)
        }
        fun send(action: Int,atX: Float=x,atY: Float=y) {
            instrumentation.runOnMainSync {
                MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,atX,atY,0).let {
                    it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN; activity.dispatchTouchEvent(it); it.recycle()
                }
            }
        }
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
            scenario.onActivity { activity ->
                assertNull("Offscreen yearly records must not be inflated on entry",activity.findViewById<View>(R.id.usage_records))
            }
            toggleCalendar(scenario)
            awaitMonth(scenario,YearMonth.now())
            scenario.onActivity { activity ->
                assertEquals(UsageRange.MONTH,monthChart(activity)!!.period.range)
                assertNull("Monthly browsing uses the carousel instead of arrow controls",activity.findViewById<View>(R.id.usage_calendar_previous))
                assertNull(activity.findViewById<View>(R.id.usage_calendar_next))
            }
            scrollMonth(scenario,previous=true)
            awaitMonth(scenario,YearMonth.now().minusMonths(1))
            awaitReport(scenario,LocalDate.now().minusDays(1))
            scrollMonth(scenario,previous=false)
            awaitMonth(scenario,YearMonth.now())
            scenario.onActivity { activity ->
                val carousel=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_month_carousel)
                assertTrue(carousel.fling(if(carousel.layoutDirection==View.LAYOUT_DIRECTION_RTL) 5500 else -5500,0))
            }
            val browsed=awaitMonth(scenario)
            assertTrue("A hard fling can pass multiple months",java.time.temporal.ChronoUnit.MONTHS.between(browsed,YearMonth.now())>1)
            scenario.onActivity { activity ->
                val scale=activity.findViewById<View>(R.id.usage_calendar_scale)
                repeat(2) { assertTrue(scale.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,null)) }
            }
            awaitMonth(scenario,browsed)
            var before=""
            scroll(scenario,UsageSections.Kind.HOURS)
            var first=0; var offset=0
            scenario.onActivity { activity ->
                before=activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString()
                val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_scroll)
                val manager=list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                first=manager.findFirstVisibleItemPosition(); offset=manager.findViewByPosition(first)!!.top
            }
            scenario.recreate(); awaitReport(scenario,LocalDate.now().minusDays(1))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(before,activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString())
                val manager=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_scroll).layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                assertEquals(first,manager.findFirstVisibleItemPosition()); assertEquals(offset,manager.findViewByPosition(first)!!.top)
                val adapter=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_scroll).adapter!!
                assertEquals(UsageSections.Kind.entries.map { it.ordinal.toLong() },(0 until adapter.itemCount).map(adapter::getItemId))
            }
            scroll(scenario,UsageSections.Kind.CALENDAR); awaitMonth(scenario,browsed)
        }
    }
    @Test fun monthlyFutureDatesAreDrawnGrayAndCannotBeSelected() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val today=LocalDate.of(2026,10,8)
            var selected: LocalDate?=null
            val period=UsageSelection(UsageRange.MONTH,today,java.time.DayOfWeek.MONDAY)
            val chart=UsageHeatmapView(activity,period,emptyMap(),today,today) { selected=it }
            val parent=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            parent.addView(chart,android.view.ViewGroup.LayoutParams(900,900))
            chart.measure(View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.AT_MOST))
            chart.layout(0,0,chart.measuredWidth,chart.measuredHeight)
            val image=Bitmap.createBitmap(chart.width,chart.height,Bitmap.Config.ARGB_8888)
            chart.draw(android.graphics.Canvas(image))
            val rect=chart.bounds(today.withDayOfMonth(31))
            assertEquals(activity.usageColor(com.google.android.material.R.attr.colorSurfaceContainerHighest),image.getPixel(rect.centerX(),rect.top+3))
            image.recycle()
            val provider=chart.accessibilityNodeProvider!!
            val future=provider.createAccessibilityNodeInfo(30)!!
            assertFalse(future.isEnabled); assertFalse(future.isClickable)
            assertFalse(provider.performAction(30,AccessibilityNodeInfoCompat.ACTION_CLICK,null)); assertNull(selected)
            assertTrue(provider.performAction(7,AccessibilityNodeInfoCompat.ACTION_CLICK,null)); assertEquals(today,selected)
            parent.removeView(chart)
        } }
    }

    @Test fun poppingAnalyticsRetainsReportsAndUpdatesOnlyAffectedSections() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun navigate()=scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment)
                    .navController.navigate(R.id.usageFragment)
            }
            navigate(); awaitReport(scenario)
            lateinit var original: UsageViewModel
            scenario.onActivity { activity ->
                original=androidx.lifecycle.ViewModelProvider(activity)[UsageViewModel::class.java]
                val past=UsageSelection(UsageRange.WEEK,LocalDate.now().minusYears(1),original.selectionState.value.firstWeekday)
                original.date(past.start.plusDays(3))
            }
            awaitReport(scenario,original.selectionState.value.anchor)
            scroll(scenario,UsageSections.Kind.WEEK)
            var weekly: View?=null
            scenario.onActivity { activity ->
                original=androidx.lifecycle.ViewModelProvider(activity)[UsageViewModel::class.java]
                weekly=activity.findViewById(R.id.usage_weekly_chart)
                val selected=original.selectionState.value
                if(selected.anchor!=selected.start) activity.findViewById<View>(R.id.usage_previous).performClick()
            }
            awaitReport(scenario,original.selectionState.value.anchor)
            var prepared: UsageDashboard?=null
            scenario.onActivity { activity ->
                assertSame("A day change inside the week preserves its mounted weekly cards",weekly,activity.findViewById(R.id.usage_weekly_chart))
                prepared=original.state.value.report
                activity.onBackPressedDispatcher.onBackPressed()
            }
            navigate()
            awaitReport(scenario,original.selectionState.value.anchor)
            scenario.onActivity { activity ->
                assertSame(original,androidx.lifecycle.ViewModelProvider(activity)[UsageViewModel::class.java])
                assertSame("Reopening analytics reuses its prepared report",prepared,original.state.value.report)
                assertFalse(activity.findViewById<View>(R.id.usage_loading).isVisible)
            }
        }
    }

    @Test fun calendarSelectorUsesSharedDelayAndCommitsOnlyOnRelease(): Unit=runBlocking {
        val preferences=EntryPointAccessors.fromApplication(app,one.njk.sao.floating.FloatingTestAccess::class.java).preferences()
        val original=preferences.current().longPressMs
        preferences.longPressMs(400)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                    host.navController.navigate(R.id.usageFragment)
                }
                awaitReport(scenario)
                scroll(scenario,UsageSections.Kind.CALENDAR)
                val touch=ScaleTouch(scenario)
                SystemClock.sleep(170)
                instrumentation.runOnMainSync {
                    assertNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<View>(R.id.hold_choice_card) })
                }
                SystemClock.sleep(300)
                var row=0f
                scenario.onActivity { activity ->
                    val viewport=activity.findViewById<View>(R.id.hold_choice_viewport)
                    assertNotNull("The shared 400 ms delay opens the selector",viewport)
                    row=viewport.height.toFloat()/(activity.findViewById<android.widget.LinearLayout>(R.id.hold_choice_rows).childCount-2)
                }
                touch.send(MotionEvent.ACTION_MOVE,atY=touch.y-row*3)
                scenario.onActivity { activity ->
                    assertEquals("Dragging loops without committing",activity.getString(R.string.usage_year),activity.findViewById<android.widget.TextView>(R.id.usage_calendar_scale).text.toString())
                }
                if(InstrumentationRegistry.getArguments().getString("captureUsage")=="true") {
                    val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(app.filesDir,"usage-scale-held.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
                touch.send(MotionEvent.ACTION_UP,atY=touch.y-row*3)
                awaitMonth(scenario,YearMonth.now())
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.usage_month),activity.findViewById<android.widget.TextView>(R.id.usage_calendar_scale).text.toString())
                    assertNull(activity.findViewById<View>(R.id.hold_choice_card))
                }
            }
        } finally { preferences.longPressMs(original) }
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
            scenario.onActivity { it.findViewById<View>(R.id.usage_previous).performClick() }
            awaitReport(scenario,today.minusDays(1))
            SystemClock.sleep(80)
            val changing=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(app.filesDir,"usage-value-changing.png").outputStream().use { changing.compress(Bitmap.CompressFormat.PNG,100,it) }; changing.recycle()
            SystemClock.sleep(350)
            scenario.onActivity { activity ->
                val value=activity.findViewById<android.widget.TextView>(R.id.usage_day_total)
                assertEquals(java.text.NumberFormat.getIntegerInstance(activity.resources.configuration.locales[0]).format(24L),value.text.toString())
                assertEquals(1f,value.alpha,0f); assertEquals(0f,value.translationY,0f)
            }
            val settled=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(app.filesDir,"usage-value-settled.png").outputStream().use { settled.compress(Bitmap.CompressFormat.PNG,100,it) }; settled.recycle()
            scenario.onActivity { it.findViewById<View>(R.id.usage_today).performClick() }
            awaitReport(scenario); SystemClock.sleep(350)
            val sections=UsageSections.Kind.entries
            sections.forEachIndexed { index,kind ->
                scroll(scenario,kind)
                instrumentation.waitForIdleSync(); SystemClock.sleep(180)
                val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(app.filesDir,"usage-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            scroll(scenario,UsageSections.Kind.CALENDAR)
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
            toggleCalendar(scenario)
            awaitMonth(scenario,YearMonth.from(today.minusDays(2)))
            scenario.onActivity { activity ->
                val chart=monthChart(activity)!!
                assertEquals(UsageRange.MONTH,chart.period.range)
                assertTrue("All seven weekday columns fit the monthly viewport",chart.width<=(chart.parent as View).width)
                val node=chart.accessibilityNodeProvider!!.createAccessibilityNodeInfo(today.minusDays(2).dayOfMonth-1)!!
                assertTrue(node.isSelected); assertTrue(node.isClickable)
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(180)
            val month=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(app.filesDir,"usage-month.png").outputStream().use { month.compress(Bitmap.CompressFormat.PNG,100,it) }; month.recycle()
            scrollMonth(scenario,previous=true)
            awaitMonth(scenario,YearMonth.from(today.minusDays(2)).minusMonths(1))
            instrumentation.waitForIdleSync()
            val neighbors=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(app.filesDir,"usage-month-neighbors.png").outputStream().use { neighbors.compress(Bitmap.CompressFormat.PNG,100,it) }; neighbors.recycle()
            scrollMonth(scenario,previous=false)
            awaitMonth(scenario,YearMonth.from(today.minusDays(2)))
            scenario.onActivity { activity ->
                assertTrue(monthChart(activity)!!.accessibilityNodeProvider!!.performAction(0,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK,null))
            }
            awaitReport(scenario,today.withDayOfMonth(1))
            assertTrue(access.usageDatabase().usage().observeDays().first().isNotEmpty())
            val clipboard=EntryPointAccessors.fromApplication(app,one.njk.sao.floating.FloatingTestAccess::class.java).clipboard()
            val request=one.njk.sao.floating.ClipboardRequest()
            assertTrue(clipboard.publishFilename(one.njk.sao.source.MediaItem("usage-fixture","Fixture","","image/png",emptyList(),emptyList(),filename="never-store-this-filename.png"),request,request.next()))
            repository.flush()
            assertTrue(access.usageDatabase().usage().observeDays().first().any { it.day==today.toEpochDay() })
            assertTrue(access.usageDatabase().usage().observe(today.toEpochDay(),today.plusDays(1).toEpochDay()).first().any { it.action==UsageAction.COPY_FILENAME })
            scroll(scenario,UsageSections.Kind.PRIVACY)
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
