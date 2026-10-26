package one.njk.sao.usage

import one.njk.sao.ui.SaoMotion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.FragmentUsageBinding
import one.njk.sao.floating.FloatingOptions
import one.njk.sao.floating.FloatingPreferences
import one.njk.sao.floating.HoldDragSelector
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import javax.inject.Inject

@AndroidEntryPoint
class UsageFragment: Fragment() {
    private var _binding: FragmentUsageBinding?=null
    private val binding get()=requireNotNull(_binding)
    private val uiContext: Context get()=binding.root.context
    private val model: UsageViewModel by viewModels()
    @Inject lateinit var preferences: FloatingPreferences
    private var holdMs=FloatingOptions.DEFAULT_LONG_PRESS_MS.toLong()
    private var _motion: UsageMotion?=null
    private val motion get()=requireNotNull(_motion)
    private var scaleGesture: HoldDragSelector<UsageCalendarMode>?=null
    private var scaleMenu: PopupMenu?=null
    private var rendered: UsageDashboard?=null
    private var renderedCalendarMode: UsageCalendarMode?=null
    private var monthCarousel: UsageMonthCarousel?=null
    private var names: Map<String,String> = emptyMap()
    private var showDay=false
    private val locale get()=resources.configuration.locales[0]
    private val numbers get()=NumberFormat.getIntegerInstance(locale)
    private fun dp(value: Int)=(resources.displayMetrics.density*value).toInt()
    private val datesChanged=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) { model.refresh(locale) }
    }
    private enum class Tone(val container: Int,val foreground: Int) {
        PRIMARY(com.google.android.material.R.attr.colorPrimaryContainer,com.google.android.material.R.attr.colorOnPrimaryContainer),
        SECONDARY(com.google.android.material.R.attr.colorSecondaryContainer,com.google.android.material.R.attr.colorOnSecondaryContainer),
        TERTIARY(com.google.android.material.R.attr.colorTertiaryContainer,com.google.android.material.R.attr.colorOnTertiaryContainer),
        SURFACE(com.google.android.material.R.attr.colorSurfaceContainerHigh,com.google.android.material.R.attr.colorOnSurface)
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); SaoMotion.navigation(this) }
    override fun onCreateView(inflater: LayoutInflater,container: ViewGroup?,state: Bundle?): View {
        val themed=DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(requireContext(),R.style.Theme_SAO_Usage))
        _binding=FragmentUsageBinding.inflate(inflater.cloneInContext(themed),container,false)
        return binding.root
    }
    override fun onViewCreated(view: View,savedInstanceState: Bundle?) {
        (view as? ViewGroup)?.isTransitionGroup=true
        _motion=UsageMotion(binding.usageContent)
        binding.usagePrevious.setOnClickListener { model.step(-1) }
        binding.usageNext.setOnClickListener { model.step(1) }
        binding.usagePeriod.setOnClickListener { chooseDate() }
        binding.usageToday.setOnClickListener { model.date(LocalDate.now()) }
        binding.usageRecording.setOnClickListener { if(model.state.value.enabled!=null) model.enabled(binding.usageRecording.isChecked) }
        binding.usageClear.setOnClickListener {
            MaterialAlertDialogBuilder(uiContext).setTitle(R.string.usage_clear_title).setMessage(R.string.usage_clear_message)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.usage_clear) { _,_ -> model.clear() }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { preferences.options.map { it.longPressMs }.distinctUntilChanged().collect { holdMs=it.toLong() } }
            launch { model.selectionState.collect { selected ->
                val date=dayLabel(selected.anchor)
                binding.usagePeriod.text=date
                binding.usagePeriod.contentDescription=getString(R.string.usage_date_selection,date)
            } }
            launch { model.state.collect { state ->
                binding.usageLoading.isVisible=state.report==null && !state.error
                binding.usageError.isVisible=state.error
                binding.usageRecording.isEnabled=state.enabled!=null
                binding.usageRecording.isChecked=state.enabled==true
                binding.usageRecording.setText(if(state.enabled==false) R.string.usage_paused else R.string.usage_recording)
                binding.usageClear.isEnabled=state.report!=null
                state.report?.let { if(it.selection==model.selectionState.value && rendered!=it) render(it) }
            } }
            launch { model.sourceNames.collect { current ->
                if(names!=current) { names=current; rendered?.takeIf { it.selection==model.selectionState.value }?.let(::render) }
            } }
            launch { model.calendarMode.collect { mode ->
                if(renderedCalendarMode!=mode) rendered?.let(::calendar)
            } }
            launch { model.calendarMonth.collect { month ->
                if(renderedCalendarMode==UsageCalendarMode.MONTH) {
                    monthCarousel?.showMonth(month)
                }
            } }
        } }
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(requireContext(),datesChanged,IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onResume() { super.onResume(); model.refresh(locale) }
    override fun onStop() {
        scaleGesture?.cancel(); scaleMenu?.dismiss(); motion.stop()
        requireContext().unregisterReceiver(datesChanged); super.onStop()
    }
    override fun onDestroyView() {
        model.scroll=binding.usageScroll.scrollY
        scaleGesture?.dispose(); scaleGesture=null; scaleMenu?.dismiss(); scaleMenu=null
        motion.close(); _motion=null
        monthCarousel?.close(); monthCarousel=null
        rendered=null; renderedCalendarMode=null; _binding=null; super.onDestroyView()
    }
    private fun render(report: UsageDashboard) {
        val position=if(rendered==null) model.scroll else binding.usageScroll.scrollY
        rendered=report
        binding.usageNext.isEnabled=report.selection.anchor<report.today
        binding.usageToday.isVisible=report.selection.anchor!=report.today
        motion.beginUpdate()
        binding.usageContent.removeAllViews()
        hero(report)
        calendar(report)
        val daily=section(R.string.usage_hourly,dayLabel(report.selection.anchor)).apply { id=R.id.usage_daily_chart }
        val hours=report.day.hourly.mapIndexed { hour,value -> UsagePoint(hourLabel(hour),value) }
        daily.addView(UsageTrendView(uiContext,hours) { showPoint(hours[it]) },spaced(-1,dp(180)))
        week(report.week)
        breakdown(report.week)
        records(report)
        motion.endUpdate()
        val current=binding
        val focusDay=showDay; showDay=false
        current.usageScroll.post {
            if(_binding===current && rendered?.selection==report.selection) {
                val y=if(focusDay) (daily.parent as View).top+current.usageContent.top else position
                current.usageScroll.scrollTo(0,y)
            }
        }
    }
    private fun hero(dashboard: UsageDashboard) {
        val report=dashboard.day
        val column=section(if(report.selection.anchor==report.today) R.string.usage_today else R.string.usage_selected_day,
            tone=Tone.PRIMARY)
        val total=LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL }
        column.addView(total)
        total.addView(motion.value(R.string.usage_today,R.string.usage_actions,numbers.format(report.actions)) {
            text(it,tone=Tone.PRIMARY,appearance=com.google.android.material.R.style.TextAppearance_Material3_DisplayLarge_Emphasized).apply { id=R.id.usage_day_total }
        })
        total.addView(text(getString(R.string.usage_actions),tone=Tone.PRIMARY),LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(12) })
        if(dashboard.days.isEmpty()) column.addView(text(getString(R.string.usage_empty),tone=Tone.PRIMARY),spaced(-1,-2))
        metrics(column,listOf(R.string.usage_searches to numbers.format(report.searches),R.string.usage_copies to numbers.format(report.copies),
            R.string.usage_handoffs to numbers.format(report.handoffs)),listOf(Tone.SURFACE,Tone.SECONDARY,Tone.TERTIARY),group=R.string.usage_today)
    }
    private fun calendarLabel(mode: UsageCalendarMode)=getString(if(mode==UsageCalendarMode.MONTH) R.string.usage_month else R.string.usage_year)
    private fun chooseCalendarScale(anchor: View) {
        scaleGesture?.cancel(); scaleMenu?.dismiss()
        val menu=PopupMenu(uiContext,anchor)
        UsageCalendarMode.entries.forEach { mode ->
            menu.menu.add(0,mode.ordinal+1,mode.ordinal,calendarLabel(mode)).apply { isCheckable=true; isChecked=mode==model.calendarMode.value }
        }
        menu.menu.setGroupCheckable(0,true,true)
        menu.setOnMenuItemClickListener { item -> model.calendarMode(UsageCalendarMode.entries[item.itemId-1]); true }
        menu.setOnDismissListener { if(scaleMenu===it) scaleMenu=null }
        scaleMenu=menu; menu.show()
    }
    private fun calendar(dashboard: UsageDashboard) {
        val mode=model.calendarMode.value
        val previousMode=renderedCalendarMode
        renderedCalendarMode=mode
        val monthly=mode==UsageCalendarMode.MONTH
        val report=if(monthly) dashboard.month else dashboard.year
        val column=binding.usageContent.findViewById<LinearLayout>(R.id.usage_activity_calendar) ?: section(R.string.usage_calendar)
        column.id=R.id.usage_activity_calendar
        val oldCarousel=monthCarousel
        oldCarousel?.stopScroll(); monthCarousel=null
        var scale=column.findViewById<MaterialButton>(R.id.usage_calendar_scale)
        if(scale==null) {
            scaleGesture?.dispose(); scaleMenu?.dismiss()
            column.removeViewAt(0)
            val header=LayoutInflater.from(uiContext).inflate(R.layout.view_usage_calendar_header,column,false)
            column.addView(header,0)
            scale=header.findViewById(R.id.usage_calendar_scale)
            scale.minWidth=maxOf(scale.minWidth,kotlin.math.ceil(UsageCalendarMode.entries.maxOf { scale.paint.measureText(calendarLabel(it)) }
                +scale.paddingLeft+scale.paddingRight+scale.iconSize+scale.iconPadding).toInt())
            scale.setOnClickListener { chooseCalendarScale(it) }
            scaleGesture=HoldDragSelector(scale,requireActivity().findViewById<FrameLayout>(android.R.id.content),
                choices={ UsageCalendarMode.entries },selected={ model.calendarMode.value },label=::calendarLabel,
                identity={ model.selectionState.value to model.calendarMode.value },apply=model::calendarMode,
                title=R.string.usage_scale_title,chooseAction=R.string.usage_scale_choose,
                nextAction=R.string.usage_scale_next,previousAction=R.string.usage_scale_previous,
                opening={ scaleMenu?.dismiss() },hint={ getString(R.string.usage_scale_release) },holdDelay={ holdMs })
        }
        scale.text=calendarLabel(mode)
        scale.contentDescription=getString(R.string.usage_scale_selected,calendarLabel(mode))
        scaleGesture?.update()
        val body=LinearLayout(uiContext).apply { orientation=LinearLayout.VERTICAL }
        if(monthly) {
            val carousel=UsageMonthCarousel(uiContext,dashboard,model.calendarMonth.value,model::browseMonth,::selectUsageDay) { parent,month ->
                val average=NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits=1 }.format(if(month.activeDays==0) 0.0 else month.actions.toDouble()/month.activeDays)
                metrics(parent,listOf(R.string.usage_actions to numbers.format(month.actions),R.string.usage_active_days to numbers.format(month.activeDays),R.string.usage_average_day to average))
            }
            monthCarousel=carousel
            body.addView(carousel,LinearLayout.LayoutParams(-1,-2))
        } else {
            val controls=LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL }
            body.addView(controls)
            controls.addView(iconButton(R.drawable.ic_usage_previous,R.string.usage_previous_year) { model.stepYear(-1) }.apply { id=R.id.usage_calendar_previous })
            val label=NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed=false }.format(report.selection.anchor.year)
            controls.addView(text(label,true).apply { id=R.id.usage_calendar_period; gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,-2,1f))
            controls.addView(iconButton(R.drawable.ic_usage_next,R.string.usage_next_year) { model.stepYear(1) }.apply {
                id=R.id.usage_calendar_next; isEnabled=report.selection.endExclusive<=dashboard.today
            })
            val chart=UsageHeatmapView(uiContext,report.selection,report.dayCounts,
                dashboard.selection.anchor,dashboard.today,intensity=report.intensity,onDate=::selectUsageDay).apply { id=R.id.usage_heatmap }
            val scroll=HorizontalScrollView(uiContext).apply { isHorizontalScrollBarEnabled=false; addView(chart,ViewGroup.LayoutParams(-2,-2)) }
            body.addView(scroll,spaced(-1,-2))
            scroll.post {
                if(chart.isAttachedToWindow) {
                    val x=if(model.heatmapYear==chart.year && model.heatmapDate==dashboard.selection.anchor.toEpochDay()) model.heatmapScroll else chart.bounds(dashboard.selection.anchor).centerX()-scroll.width/2
                    scroll.scrollTo(x.coerceAtLeast(0),0)
                    scroll.setOnScrollChangeListener { _: View,x: Int,_: Int,_: Int,_: Int ->
                        model.heatmapYear=chart.year; model.heatmapDate=dashboard.selection.anchor.toEpochDay(); model.heatmapScroll=x
                    }
                }
            }
        }
        val legend=LinearLayout(uiContext).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        legend.addView(text(getString(R.string.usage_less)).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall) })
        uiContext.usageHeatColors().forEach { color -> legend.addView(View(uiContext).apply { background=rounded(color,3); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(12),dp(12)).apply { marginStart=dp(4) }) }
        legend.addView(text(getString(R.string.usage_more)).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall) },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(6) })
        body.addView(legend,spaced(-1,-2))
        body.addView(text(getString(if(monthly) R.string.usage_month_hint else R.string.usage_heatmap_hint)),spaced(-1,-2))
        if(!monthly) metrics(body,listOf(R.string.usage_active_days to dayCount(report.activeDays),
            R.string.usage_current_streak to dayCount(report.streak.current),R.string.usage_best_streak to dayCount(report.streak.best)),group=R.string.usage_calendar)
        motion.calendar(column,body,previousMode!=null && previousMode!=mode) { oldCarousel?.close() }
    }
    private fun selectUsageDay(date: LocalDate) {
        if(date==model.selectionState.value.anchor) binding.usageScroll.smoothScrollTo(0,(binding.root.findViewById<View>(R.id.usage_daily_chart).parent as View).top+binding.usageContent.top)
        else { showDay=true; model.date(date) }
    }
    private fun week(report: UsageReport) {
        val period=getString(R.string.usage_week_range,shortDate(report.selection.start),dayLabel(report.selection.endExclusive.minusDays(1)))
        section(R.string.usage_daily_trend,period).apply {
            id=R.id.usage_weekly_chart
            metrics(this,listOf(R.string.usage_actions to numbers.format(report.actions),R.string.usage_handoffs to numbers.format(report.handoffs)),listOf(Tone.SECONDARY,Tone.TERTIARY),group=R.string.usage_daily_trend)
            val points=report.daily.mapIndexed { index,value -> UsagePoint(report.selection.start.plusDays(index.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT,locale),value) }
            addView(UsageTrendView(context,points,true) { index -> model.date(report.selection.start.plusDays(index.toLong())) },spaced(-1,dp(180)))
        }
        section(R.string.usage_destinations,period).apply {
            id=R.id.usage_destinations
            val points=report.targets.map { UsagePoint(target(it.key),it.count) }
            val total=points.sumOf { it.value }
            val compact=resources.configuration.fontScale<=1.3f && resources.configuration.screenWidthDp>=360
            val content=LinearLayout(context).apply { orientation=if(compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL }
            addView(content,spaced(-1,-2))
            content.addView(UsageDonutView(context,points),if(compact) LinearLayout.LayoutParams(dp(136),dp(160)) else LinearLayout.LayoutParams(-1,dp(180)))
            val legend=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            content.addView(legend,if(compact) LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12) } else LinearLayout.LayoutParams(-1,-2))
            if(points.isEmpty()) legend.addView(text(getString(R.string.usage_empty_period)))
            points.forEachIndexed { index,point -> row(legend,point.label,getString(R.string.usage_percent_count,numbers.format(point.value),NumberFormat.getPercentInstance(locale).format(point.value.toDouble()/total)),context.usageSeriesColors()[index%4]) }
            addView(text(getString(R.string.usage_destination_note)),spaced(-1,-2))
        }
    }
    private fun breakdown(report: UsageReport) {
        section(R.string.usage_action_breakdown,getString(R.string.usage_week_scope)).apply {
            id=R.id.usage_breakdown
            if(report.actionCounts.isEmpty()) addView(text(getString(R.string.usage_empty_period)))
            report.actionCounts.forEach { row(this,getString(actionLabel(it.key)),numbers.format(it.count)) }
        }
        section(R.string.usage_media_sources,getString(R.string.usage_week_scope)).apply {
            if(report.media.isEmpty() && report.sources.isEmpty()) addView(text(getString(R.string.usage_empty_period)))
            report.media.forEach { row(this,getString(when(it.key) {
                UsageMedia.VIDEO -> R.string.usage_video; UsageMedia.ANIMATION -> R.string.usage_animation; else -> R.string.usage_image
            }),numbers.format(it.count)) }
            if(report.sources.isNotEmpty()) {
                subheading(this,R.string.usage_sources)
                report.sources.forEach { row(this,names[it.key]?.let { name -> one.njk.sao.floating.sourceDisplayName(uiContext,it.key,name) } ?: getString(R.string.usage_removed_source),numbers.format(it.count)) }
            }
            if(report.outcomes.isNotEmpty()) {
                subheading(this,R.string.usage_outcomes)
                report.outcomes.forEach { row(this,getString(when(it.key) {
                    UsageOutcome.ACCEPTED -> R.string.usage_accepted; UsageOutcome.FAILED -> R.string.usage_failed; UsageOutcome.UNCERTAIN -> R.string.usage_uncertain
                }),numbers.format(it.count)) }
            }
        }
        report.searchHealth?.let { searches -> section(R.string.usage_search_health,getString(R.string.usage_week_scope)).apply {
            metrics(this,listOf(R.string.usage_search_results to numbers.format(searches.results),
                R.string.usage_search_latency to getString(R.string.usage_milliseconds,numbers.format(searches.latencyMillis/searches.count))),listOf(Tone.SECONDARY,Tone.PRIMARY),group=R.string.usage_search_health)
            searches.modes.forEach { (mode,count) ->
                val label=when(mode) {
                    UsageSearchMode.FILENAME -> R.string.search_mode_filename; UsageSearchMode.DESCRIPTION -> R.string.search_mode_description
                    UsageSearchMode.PATH -> R.string.search_mode_path; UsageSearchMode.OCR -> R.string.search_mode_ocr
                    UsageSearchMode.RANDOM -> R.string.usage_random; UsageSearchMode.CONTEXT -> R.string.search_mode_context; UsageSearchMode.NONE -> R.string.usage_searches
                }
                row(this,getString(label),numbers.format(count))
            }
        } }
    }
    private fun records(dashboard: UsageDashboard) {
        val report=dashboard.year
        section(R.string.usage_records,NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed=false }.format(report.selection.anchor.year)).apply {
            id=R.id.usage_records
            metrics(this,listOf(R.string.usage_actions to numbers.format(report.actions),R.string.usage_searches to numbers.format(report.searches),
                R.string.usage_copies to numbers.format(report.copies),R.string.usage_handoffs to numbers.format(report.handoffs)),group=R.string.usage_records)
            subheading(this,R.string.usage_busiest)
            row(this,getString(R.string.usage_best_day),uiContext.actionCount(report.bestDay))
            row(this,getString(R.string.usage_best_week),uiContext.actionCount(report.bestWeek))
            row(this,getString(R.string.usage_best_month),uiContext.actionCount(report.bestMonth))
            row(this,getString(R.string.usage_year_streak),dayCount(report.periodStreak.best))
            val average=NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits=1 }.format(if(report.activeDays==0) 0.0 else report.actions.toDouble()/report.activeDays)
            row(this,getString(R.string.usage_average_day),average)
            subheading(this,R.string.usage_lifetime)
            metrics(this,listOf(R.string.usage_actions to numbers.format(dashboard.lifetime.actions),R.string.usage_active_days to dayCount(dashboard.lifetime.activeDays),
                R.string.usage_searches to numbers.format(dashboard.lifetime.searches),R.string.usage_copies to numbers.format(dashboard.lifetime.copies)),listOf(Tone.SURFACE),group=R.string.usage_lifetime)
        }
    }
    private fun section(title: Int,subtitle: String?=null,tone: Tone?=null): LinearLayout {
        val card=MaterialCardView(uiContext).apply {
            radius=dp(if(tone==Tone.PRIMARY || title==R.string.usage_calendar) 32 else 28).toFloat(); cardElevation=0f; strokeWidth=0
            if(tone==Tone.PRIMARY) shapeAppearanceModel=shapeAppearanceModel.toBuilder()
                .setBottomLeftCornerSize(dp(if(resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL) 32 else 8).toFloat())
                .setBottomRightCornerSize(dp(if(resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL) 8 else 32).toFloat()).build()
            setCardBackgroundColor(context.usageColor(tone?.container ?: com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        binding.usageContent.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val column=LinearLayout(uiContext).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        card.addView(column)
        column.addView(text(getString(title),true,tone))
        subtitle?.let { column.addView(text(it),spaced(-1,-2)) }
        return column
    }
    private fun subheading(parent: LinearLayout,title: Int) {
        parent.addView(text(getString(title),true).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(context.usageColor(com.google.android.material.R.attr.colorTertiary))
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16); bottomMargin=dp(4) })
    }
    private fun text(value: String,title: Boolean=false,tone: Tone?=null,appearance: Int?=null)=MaterialTextView(uiContext).apply {
        text=value
        setTextAppearance(appearance ?: if(title) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium_Emphasized else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(context.usageColor(tone?.foreground ?: if(title) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
        if(title) ViewCompat.setAccessibilityHeading(this,true)
    }
    private fun metrics(parent: LinearLayout,values: List<Pair<Int,String>>,tones: List<Tone> = listOf(Tone.PRIMARY,Tone.SECONDARY,Tone.TERTIARY),group: Int?=null) {
        val columns=when { resources.configuration.fontScale>1.5f -> 1; values.size==3 -> if(resources.configuration.screenWidthDp>=360) 3 else 1; else -> 2 }
        val grid=GridLayout(uiContext).apply { columnCount=columns }
        parent.addView(grid,spaced(-1,-2))
        values.forEachIndexed { index,(label,value) ->
            val tone=tones[index%tones.size]
            val tile=MaterialCardView(uiContext).apply {
                shapeAppearanceModel=metricShape(index,columns,values.size)
                cardElevation=0f; strokeWidth=0; setCardBackgroundColor(context.usageColor(tone.container))
            }
            grid.addView(tile,GridLayout.LayoutParams(GridLayout.spec(index/columns),GridLayout.spec(index%columns,1f)).apply {
                width=0; height=-2; setGravity(Gravity.FILL); setMargins(dp(1),dp(1),dp(1),dp(1))
            })
            tile.addView(LinearLayout(uiContext).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(12))
                addView(text(getString(label),tone=tone,appearance=com.google.android.material.R.style.TextAppearance_Material3_LabelMedium_Emphasized))
                addView(android.widget.Space(context),LinearLayout.LayoutParams(0,0,1f))
                val factory={ formatted: String -> text(formatted,tone=tone,appearance=com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall_Emphasized) }
                addView(if(group==null) factory(value) else motion.value(group,label,value,factory),spaced(-1,-2))
            })
        }
    }
    private fun metricShape(index: Int,columns: Int,count: Int): ShapeAppearanceModel {
        val row=index/columns; val column=index%columns; val rows=(count+columns-1)/columns
        val outer=dp(20).toFloat(); val inner=dp(4).toFloat()
        val topStart=if(row==0 && column==0) outer else inner
        val topEnd=if(row==0 && column==columns-1) outer else inner
        val bottomStart=if(row==rows-1 && column==0) outer else inner
        val bottomEnd=if(row==rows-1 && index==count-1) outer else inner
        val rtl=resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL
        return ShapeAppearanceModel.builder().setTopLeftCornerSize(if(rtl) topEnd else topStart)
            .setTopRightCornerSize(if(rtl) topStart else topEnd)
            .setBottomLeftCornerSize(if(rtl) bottomEnd else bottomStart)
            .setBottomRightCornerSize(if(rtl) bottomStart else bottomEnd).build()
    }
    private fun row(parent: LinearLayout,label: String,value: String,color: Int?=null) {
        val row=LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(7),0,dp(7)) }
        parent.addView(row,LinearLayout.LayoutParams(-1,-2))
        if(color!=null) row.addView(View(uiContext).apply { background=rounded(color,4); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(8),dp(8)).apply { marginEnd=dp(8) })
        row.addView(text(label),LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(8) })
        row.addView(text(value,tone=Tone.SURFACE,appearance=com.google.android.material.R.style.TextAppearance_Material3_LabelLarge_Emphasized),LinearLayout.LayoutParams(-2,-2))
    }
    private fun iconButton(iconRes: Int,label: Int,action: ()->Unit)=
        (LayoutInflater.from(uiContext).inflate(R.layout.view_usage_navigation_button,binding.usageContent,false) as MaterialButton).apply {
            icon=ContextCompat.getDrawable(context,iconRes); contentDescription=getString(label)
            setOnClickListener { action() }
        }
    private fun rounded(color: Int,radius: Int)=MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(radius).toFloat()).build()).apply { fillColor=ColorStateList.valueOf(color) }
    private fun spaced(width: Int,height: Int)=LinearLayout.LayoutParams(width,height).apply { topMargin=dp(8) }
    private fun dayLabel(date: LocalDate)=date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    private fun shortDate(date: LocalDate)=date.format(DateTimeFormatter.ofPattern("MMM d",locale))
    private fun hourLabel(hour: Int)=java.time.LocalTime.of(hour,0).format(DateTimeFormatter.ofPattern("HH:mm",locale).withDecimalStyle(java.time.format.DecimalStyle.of(locale)))
    private fun dayCount(value: Int)=resources.getQuantityString(R.plurals.usage_days_count,value,numbers.format(value))
    private fun chooseDate() {
        val report=rendered ?: return
        if(parentFragmentManager.findFragmentByTag("usage_date")!=null) return
        val picker=MaterialDatePicker.Builder.datePicker().setTitleText(R.string.usage_choose_date)
            .setSelection(report.selection.anchor.toEpochDay()*86_400_000)
            .setCalendarConstraints(CalendarConstraints.Builder().setEnd(report.today.toEpochDay()*86_400_000)
                .setValidator(DateValidatorPointBackward.before(report.today.toEpochDay()*86_400_000)).build()).build()
        picker.addOnPositiveButtonClickListener { epoch -> model.date(Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDate()) }
        picker.show(parentFragmentManager,"usage_date")
    }
    private fun showPoint(point: UsagePoint) { Snackbar.make(binding.root,getString(R.string.usage_point_value,point.label,uiContext.actionCount(point.value)),Snackbar.LENGTH_SHORT).show() }
    private fun actionLabel(action: UsageAction)=when(action) {
        UsageAction.SEARCH -> R.string.usage_searches; UsageAction.COPY_MEDIA -> R.string.usage_copies
        UsageAction.COPY_FILENAME -> R.string.usage_filename; UsageAction.PASTE -> R.string.usage_pastes
        UsageAction.SHARE -> R.string.usage_share; UsageAction.STICKER -> R.string.usage_stickers
        UsageAction.DOWNLOAD -> R.string.usage_downloads; UsageAction.BOOKMARK -> R.string.usage_bookmarks
    }
    private fun target(target: UsageTarget)=getString(when(target) {
        UsageTarget.WHATSAPP -> R.string.usage_whatsapp; UsageTarget.TELEGRAM -> R.string.usage_telegram
        UsageTarget.DISCORD -> R.string.usage_discord; UsageTarget.INSTAGRAM -> R.string.usage_instagram; else -> R.string.usage_other_app
    })
}
