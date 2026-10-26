package one.njk.sao.usage

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
                val model=androidx.lifecycle.ViewModelProvider(fragment)[UsageViewModel::class.java]
                ready=model.state.value.report?.selection==model.selectionState.value && model.selectionState.value.anchor==date &&
                    activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period)?.text?.isNotBlank()==true
            }
            if(!ready) SystemClock.sleep(40)
        } while(!ready && SystemClock.elapsedRealtime()<deadline)
        assertTrue("Expected the selected report to finish rendering: $date",ready)
    }
    private fun calendarChart(activity: MainActivity,mode: UsageCalendarMode): UsageHeatmapView? {
        val carousel=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(if(mode==UsageCalendarMode.MONTH) R.id.usage_month_carousel else R.id.usage_year_carousel) ?: return null
        val root=com.google.android.material.carousel.CarouselSnapHelper(false).findSnapView(carousel.layoutManager) ?: return null
        fun chart(view: View): UsageHeatmapView? {
            if(view is UsageHeatmapView) return view
            if(view is android.view.ViewGroup) for(index in 0 until view.childCount) chart(view.getChildAt(index))?.let { return it }
            return null
        }
        return chart(root)
    }
    private fun monthChart(activity: MainActivity)=calendarChart(activity,UsageCalendarMode.MONTH)
    private fun yearChart(activity: MainActivity)=calendarChart(activity,UsageCalendarMode.YEAR)
    private fun awaitMonth(scenario: ActivityScenario<MainActivity>,expected: YearMonth?=null)=YearMonth.from(awaitPeriod(scenario,UsageCalendarMode.MONTH,expected?.atDay(1)))
    private fun awaitYear(scenario: ActivityScenario<MainActivity>,expected: Int)=awaitPeriod(scenario,UsageCalendarMode.YEAR,LocalDate.of(expected,1,1)).year
    private fun awaitPeriod(scenario: ActivityScenario<MainActivity>,mode: UsageCalendarMode,expected: LocalDate?=null): LocalDate {
        val deadline=SystemClock.elapsedRealtime()+8000
        var actual: LocalDate?=null
        do {
            scenario.onActivity { activity ->
                val carousel=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(if(mode==UsageCalendarMode.MONTH) R.id.usage_month_carousel else R.id.usage_year_carousel) ?: return@onActivity
                if(carousel.scrollState!=androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return@onActivity
                val manager=carousel.layoutManager ?: return@onActivity
                val snap=com.google.android.material.carousel.CarouselSnapHelper(false)
                val focused=snap.findSnapView(manager) ?: return@onActivity
                if(snap.calculateDistanceToFinalSnap(manager,focused)?.any { kotlin.math.abs(it)>1 }==true) return@onActivity
                val chart=calendarChart(activity,mode) ?: return@onActivity
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val fragment=host.childFragmentManager.primaryNavigationFragment!!
                val model=androidx.lifecycle.ViewModelProvider(fragment)[UsageViewModel::class.java]
                val period=chart.period.anchor
                val selected=if(mode==UsageCalendarMode.MONTH) model.calendarMonth.value.atDay(1) else LocalDate.of(model.calendarYear.value,1,1)
                if(period==selected && (expected==null || expected==period) && chart.isShown && chart.width>0) actual=period
            }
            if(actual==null) SystemClock.sleep(40)
        } while(actual==null && SystemClock.elapsedRealtime()<deadline)
        assertNotNull("The carousel must settle on its displayed period: $expected",actual)
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
        scenario.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.usage_calendar_scale)
            .performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,null)) }
    }
    private inner class CalendarTouch(scenario: ActivityScenario<MainActivity>,target: (MainActivity)->View={ it.findViewById(R.id.usage_calendar_scale) },edgeDrag: Boolean=false) {
        private lateinit var activity: MainActivity
        private val down=SystemClock.uptimeMillis()
        var x=0f; var y=0f
        init {
            scenario.onActivity {
                activity=it
                val button=target(it)
                val location=IntArray(2).also(button::getLocationInWindow)
                val fraction=if(edgeDrag) { if(button.layoutDirection==View.LAYOUT_DIRECTION_RTL) .85f else .15f } else .5f
                x=location[0]+button.width*fraction; y=location[1]+button.height/2f
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
            var daily: View?=null
            scenario.onActivity { activity ->
                daily=activity.findViewById(R.id.usage_daily_chart)
            }
            toggleCalendar(scenario)
            awaitMonth(scenario,YearMonth.now())
            scenario.onActivity { activity ->
                assertSame("Changing calendar mode keeps the other reports intact",daily,activity.findViewById(R.id.usage_daily_chart))
                assertEquals(UsageRange.MONTH,monthChart(activity)!!.period.range)
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
            scenario.onActivity { activity -> assertSame("Browsing months keeps detailed reports mounted",daily,activity.findViewById(R.id.usage_daily_chart)) }
            var before=""
            var scroll=0
            scenario.onActivity { activity ->
                before=activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString()
                activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,700)
                scroll=activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollY
            }
            scenario.recreate(); awaitReport(scenario,LocalDate.now().minusDays(1))
            awaitMonth(scenario,browsed)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(before,activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_period).text.toString())
                assertEquals(scroll,activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollY)
                assertEquals(activity.getString(R.string.usage_month),activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.usage_calendar_scale).text.toString())
                val ids=listOf(R.id.usage_activity_calendar,R.id.usage_daily_chart,R.id.usage_weekly_chart,R.id.usage_destinations,R.id.usage_breakdown,R.id.usage_records)
                val tops=ids.map { (activity.findViewById<View>(it).parent as View).top }
                assertEquals("Reports appear together in one scrolling page",tops.sorted(),tops)
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
                scenario.onActivity { activity ->
                    val card=activity.findViewById<View>(R.id.usage_activity_calendar).parent as View
                    activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,card.top+activity.findViewById<View>(R.id.usage_content).top)
                }
                val touch=CalendarTouch(scenario)
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
    @Test fun yearStripScrollsBeforeCarouselAndRestoresBothPositions(): Unit=runBlocking {
        val today=LocalDate.now()
        val year=today.year-1 // A complete year keeps the gesture check valid even in early January.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                host.navController.navigate(R.id.usageFragment)
            }
            awaitReport(scenario); awaitYear(scenario,today.year)
            scenario.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.usage_year_carousel)
                .performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,null)) }
            awaitYear(scenario,year)
            val density=app.resources.displayMetrics.density
            var direction=1f
            var daily: View?=null
            var stripBefore=0
            scenario.onActivity { activity ->
                daily=activity.findViewById(R.id.usage_daily_chart)
                val card=activity.findViewById<View>(R.id.usage_activity_calendar).parent as View
                activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,card.top+activity.findViewById<View>(R.id.usage_content).top)
                val strip=yearChart(activity)!!.parent as android.widget.HorizontalScrollView
                direction=if(strip.layoutDirection==View.LAYOUT_DIRECTION_RTL) -1f else 1f
                strip.scrollTo((strip.getChildAt(0).width-strip.width)/2,0); stripBefore=strip.scrollX
            }
            val inner=CalendarTouch(scenario,{ yearChart(it)!!.parent as View },edgeDrag=true)
            for(step in 1..6) { inner.send(MotionEvent.ACTION_MOVE,atX=inner.x+direction*72*density*step/6); SystemClock.sleep(16) }
            scenario.onActivity { activity ->
                assertNotEquals("The inner strip moves before its carousel",stripBefore,(yearChart(activity)!!.parent as View).scrollX)
                assertEquals(androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE,activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_year_carousel).scrollState)
            }
            SystemClock.sleep(120); inner.send(MotionEvent.ACTION_UP,atX=inner.x+direction*72*density)
            awaitYear(scenario,year)
            scenario.onActivity { activity ->
                val strip=yearChart(activity)!!.parent as android.widget.HorizontalScrollView
                val maximum=strip.getChildAt(0).width-strip.width
                strip.scrollTo(if(direction>0) (32*density).toInt() else maximum-(32*density).toInt(),0)
            }
            val edge=CalendarTouch(scenario,{ yearChart(it)!!.parent as View },edgeDrag=true)
            for(step in 1..4) { edge.send(MotionEvent.ACTION_MOVE,atX=edge.x+direction*24*density*step/4); SystemClock.sleep(16) }
            scenario.onActivity { activity ->
                assertTrue("The carousel waits until the strip reaches its edge",(yearChart(activity)!!.parent as View).canScrollHorizontally(if(direction>0) -1 else 1))
                assertEquals(androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE,activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.usage_year_carousel).scrollState)
            }
            for(step in 1..20) { edge.send(MotionEvent.ACTION_MOVE,atX=edge.x+direction*(24+236*step/20f)*density); SystemClock.sleep(16) }
            SystemClock.sleep(120); edge.send(MotionEvent.ACTION_UP,atX=edge.x+direction*260*density)
            awaitYear(scenario,year-1); awaitReport(scenario,today)
            var offset=0
            scenario.onActivity { activity ->
                assertSame("Browsing years keeps detailed reports mounted",daily,activity.findViewById(R.id.usage_daily_chart))
                val strip=yearChart(activity)!!.parent as android.widget.HorizontalScrollView
                strip.scrollTo((strip.getChildAt(0).width-strip.width)/3,0); offset=strip.scrollX
            }
            if(InstrumentationRegistry.getArguments().getString("captureUsage")=="true") {
                val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(app.filesDir,"usage-year-neighbors.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            scenario.recreate(); awaitReport(scenario,today); awaitYear(scenario,year-1)
            scenario.onActivity { activity -> assertEquals("Each year's strip position survives recreation",offset,(yearChart(activity)!!.parent as View).scrollX) }
            toggleCalendar(scenario); awaitMonth(scenario,YearMonth.now())
            toggleCalendar(scenario); awaitYear(scenario,year-1)
            scenario.onActivity { activity -> assertEquals(offset,(yearChart(activity)!!.parent as View).scrollX) }
            scenario.onActivity { activity ->
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val model=androidx.lifecycle.ViewModelProvider(host.childFragmentManager.primaryNavigationFragment!!)[UsageViewModel::class.java]
                model.date(today)
            }
            awaitYear(scenario,today.year)
            scenario.onActivity { activity ->
                val chart=yearChart(activity)!!
                val strip=chart.parent as android.widget.HorizontalScrollView
                strip.scrollTo(if(direction>0) chart.width-strip.width else 0,0)
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                androidx.lifecycle.ViewModelProvider(host.childFragmentManager.primaryNavigationFragment!!)[UsageViewModel::class.java].date(today.withDayOfYear(1))
            }
            awaitReport(scenario,today.withDayOfYear(1)); awaitYear(scenario,today.year)
            scenario.onActivity { activity ->
                val chart=yearChart(activity)!!
                val strip=chart.parent as View
                val expected=(chart.bounds(today.withDayOfYear(1)).centerX()-strip.width/2).coerceIn(0,(chart.width-strip.width).coerceAtLeast(0))
                assertEquals("A new selected date recenters its year, ignoring retired cards' scroll snapshots",expected,strip.scrollX)
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
                val chart=yearChart(activity)!!
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
                val card=activity.findViewById<View>(R.id.usage_activity_calendar).parent as View
                activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.usage_scroll).scrollTo(0,card.top+activity.findViewById<View>(R.id.usage_content).top)
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
