package one.njk.sao.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
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
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.FragmentUsageBinding
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@AndroidEntryPoint
class UsageFragment: Fragment() {
    private var _binding: FragmentUsageBinding?=null
    private val binding get()=requireNotNull(_binding)
    private val model: UsageViewModel by viewModels()
    private var rendered: UsageDashboard?=null
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
    override fun onCreateView(inflater: LayoutInflater,container: ViewGroup?,state: Bundle?): View {
        _binding=FragmentUsageBinding.inflate(inflater,container,false)
        return binding.root
    }
    override fun onViewCreated(view: View,savedInstanceState: Bundle?) {
        binding.usagePrevious.setOnClickListener { model.step(-1) }
        binding.usageNext.setOnClickListener { model.step(1) }
        binding.usagePeriod.setOnClickListener { chooseDate() }
        binding.usageToday.setOnClickListener { model.date(LocalDate.now()) }
        binding.usageRecording.setOnClickListener { if(model.state.value.enabled!=null) model.enabled(binding.usageRecording.isChecked) }
        binding.usageClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.usage_clear_title).setMessage(R.string.usage_clear_message)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.usage_clear) { _,_ -> model.clear() }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
        } }
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(requireContext(),datesChanged,IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onResume() { super.onResume(); model.refresh(locale) }
    override fun onStop() { requireContext().unregisterReceiver(datesChanged); super.onStop() }
    override fun onDestroyView() {
        model.scroll=binding.usageScroll.scrollY
        rendered=null; _binding=null; super.onDestroyView()
    }
    private fun render(report: UsageDashboard) {
        val position=if(rendered==null) model.scroll else binding.usageScroll.scrollY
        rendered=report
        binding.usageNext.isEnabled=report.selection.anchor<report.today
        binding.usageToday.isVisible=report.selection.anchor!=report.today
        binding.usageContent.removeAllViews()
        hero(report)
        calendar(report)
        val daily=section(R.string.usage_hourly,dayLabel(report.selection.anchor)).apply { id=R.id.usage_daily_chart }
        val hours=report.day.hourly.mapIndexed { hour,value -> UsagePoint(hourLabel(hour),value) }
        daily.addView(UsageTrendView(requireContext(),hours) { showPoint(hours[it]) },spaced(-1,dp(180)))
        week(report.week)
        breakdown(report.week)
        records(report)
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
        val total=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL }
        column.addView(total)
        total.addView(text(numbers.format(report.actions),tone=Tone.PRIMARY,appearance=com.google.android.material.R.style.TextAppearance_Material3_DisplayMedium).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        total.addView(text(getString(R.string.usage_actions),tone=Tone.PRIMARY),LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(12) })
        if(dashboard.days.isEmpty()) column.addView(text(getString(R.string.usage_empty),tone=Tone.PRIMARY),spaced(-1,-2))
        metrics(column,listOf(R.string.usage_searches to numbers.format(report.searches),R.string.usage_copies to numbers.format(report.copies),
            R.string.usage_handoffs to numbers.format(report.handoffs)),listOf(Tone.SURFACE,Tone.SECONDARY,Tone.TERTIARY))
    }
    private fun calendar(dashboard: UsageDashboard) {
        val report=dashboard.year
        val column=section(R.string.usage_calendar)
        column.id=R.id.usage_activity_calendar
        val controls=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL }
        column.addView(controls)
        controls.addView(iconButton(R.drawable.ic_usage_previous,R.string.usage_previous_year) { model.year(-1) })
        controls.addView(text(NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed=false }.format(report.selection.anchor.year),true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(iconButton(R.drawable.ic_usage_next,R.string.usage_next_year) { model.year(1) }.apply {
            isEnabled=report.selection.anchor.year<dashboard.today.year
        })
        val chart=UsageHeatmapView(requireContext(),report.selection.anchor.year,report.periodDays.associate { it.day to it.actions },
            dashboard.selection.anchor,dashboard.today,dashboard.selection.firstWeekday) { date ->
            if(date==model.selectionState.value.anchor) binding.usageScroll.smoothScrollTo(0,(binding.root.findViewById<View>(R.id.usage_daily_chart).parent as View).top+binding.usageContent.top)
            else { showDay=true; model.date(date) }
        }.apply { id=R.id.usage_heatmap }
        val scroll=HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled=false
            addView(chart,ViewGroup.LayoutParams(-2,-2))
        }
        column.addView(scroll,spaced(-1,-2))
        scroll.post {
            if(chart.isAttachedToWindow) {
                val x=if(model.heatmapYear==chart.year && model.heatmapDate==dashboard.selection.anchor.toEpochDay()) model.heatmapScroll else chart.bounds(dashboard.selection.anchor).centerX()-scroll.width/2
                scroll.scrollTo(x.coerceAtLeast(0),0)
                scroll.setOnScrollChangeListener { _: View,x: Int,_: Int,_: Int,_: Int ->
                    model.heatmapYear=chart.year; model.heatmapDate=dashboard.selection.anchor.toEpochDay(); model.heatmapScroll=x
                }
            }
        }
        val legend=LinearLayout(requireContext()).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        legend.addView(text(getString(R.string.usage_less)).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall) })
        requireContext().usageHeatColors().forEach { color -> legend.addView(View(requireContext()).apply { background=rounded(color,3) },LinearLayout.LayoutParams(dp(12),dp(12)).apply { marginStart=dp(4) }) }
        legend.addView(text(getString(R.string.usage_more)).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall) },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(6) })
        column.addView(legend,spaced(-1,-2))
        column.addView(text(getString(R.string.usage_heatmap_hint)),spaced(-1,-2))
        metrics(column,listOf(R.string.usage_active_days to dayCount(report.activeDays),
            R.string.usage_current_streak to dayCount(report.streak.current),R.string.usage_best_streak to dayCount(report.streak.best)))
    }
    private fun week(report: UsageReport) {
        val period=getString(R.string.usage_week_range,shortDate(report.selection.start),dayLabel(report.selection.endExclusive.minusDays(1)))
        section(R.string.usage_daily_trend,period).apply {
            id=R.id.usage_weekly_chart
            metrics(this,listOf(R.string.usage_actions to numbers.format(report.actions),R.string.usage_handoffs to numbers.format(report.handoffs)),listOf(Tone.SECONDARY,Tone.TERTIARY))
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
                report.sources.forEach { row(this,names[it.key]?.let { name -> one.njk.sao.floating.sourceDisplayName(requireContext(),it.key,name) } ?: getString(R.string.usage_removed_source),numbers.format(it.count)) }
            }
            if(report.outcomes.isNotEmpty()) {
                subheading(this,R.string.usage_outcomes)
                report.outcomes.forEach { row(this,getString(when(it.key) {
                    UsageOutcome.ACCEPTED -> R.string.usage_accepted; UsageOutcome.FAILED -> R.string.usage_failed; UsageOutcome.UNCERTAIN -> R.string.usage_uncertain
                }),numbers.format(it.count)) }
            }
        }
        val searches=report.buckets.filter { it.action==UsageAction.SEARCH }
        if(searches.isNotEmpty()) section(R.string.usage_search_health,getString(R.string.usage_week_scope)).apply {
            val count=searches.sumOf { it.occurrences }
            metrics(this,listOf(R.string.usage_search_results to numbers.format(searches.sumOf { it.results }),
                R.string.usage_search_latency to getString(R.string.usage_milliseconds,numbers.format(searches.sumOf { it.latencyMillis }/count))),listOf(Tone.SECONDARY,Tone.PRIMARY))
            searches.groupBy { it.searchMode }.entries.sortedByDescending { (_,rows) -> rows.sumOf { it.occurrences } }.forEach { (mode,rows) ->
                val label=when(mode) {
                    UsageSearchMode.FILENAME -> R.string.search_mode_filename; UsageSearchMode.DESCRIPTION -> R.string.search_mode_description
                    UsageSearchMode.PATH -> R.string.search_mode_path; UsageSearchMode.OCR -> R.string.search_mode_ocr
                    UsageSearchMode.RANDOM -> R.string.usage_random; UsageSearchMode.CONTEXT -> R.string.search_mode_context; UsageSearchMode.NONE -> R.string.usage_searches
                }
                row(this,getString(label),numbers.format(rows.sumOf { it.occurrences }))
            }
        }
    }
    private fun records(dashboard: UsageDashboard) {
        val report=dashboard.year
        section(R.string.usage_records,NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed=false }.format(report.selection.anchor.year)).apply {
            id=R.id.usage_records
            metrics(this,listOf(R.string.usage_actions to numbers.format(report.actions),R.string.usage_searches to numbers.format(report.searches),
                R.string.usage_copies to numbers.format(report.copies),R.string.usage_handoffs to numbers.format(report.handoffs)))
            subheading(this,R.string.usage_busiest)
            row(this,getString(R.string.usage_best_day),requireContext().actionCount(report.bestDay))
            row(this,getString(R.string.usage_best_week),requireContext().actionCount(report.bestWeek))
            row(this,getString(R.string.usage_best_month),requireContext().actionCount(report.bestMonth))
            row(this,getString(R.string.usage_year_streak),dayCount(report.periodStreak.best))
            val average=NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits=1 }.format(if(report.activeDays==0) 0.0 else report.actions.toDouble()/report.activeDays)
            row(this,getString(R.string.usage_average_day),average)
            subheading(this,R.string.usage_lifetime)
            metrics(this,listOf(R.string.usage_actions to numbers.format(dashboard.days.sumOf { it.actions }),R.string.usage_active_days to dayCount(dashboard.days.size),
                R.string.usage_searches to numbers.format(dashboard.days.sumOf { it.searches }),R.string.usage_copies to numbers.format(dashboard.days.sumOf { it.copies })),listOf(Tone.SURFACE))
        }
    }
    private fun section(title: Int,subtitle: String?=null,tone: Tone?=null): LinearLayout {
        val card=MaterialCardView(requireContext()).apply {
            radius=dp(if(tone==Tone.PRIMARY) 28 else 24).toFloat(); cardElevation=0f; strokeWidth=0
            setCardBackgroundColor(context.usageColor(tone?.container ?: com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        binding.usageContent.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val column=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)) }
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
    private fun text(value: String,title: Boolean=false,tone: Tone?=null,appearance: Int?=null)=MaterialTextView(requireContext()).apply {
        text=value
        setTextAppearance(appearance ?: if(title) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(context.usageColor(tone?.foreground ?: if(title) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
        if(title) ViewCompat.setAccessibilityHeading(this,true)
    }
    private fun metrics(parent: LinearLayout,values: List<Pair<Int,String>>,tones: List<Tone> = listOf(Tone.PRIMARY,Tone.SECONDARY,Tone.TERTIARY)) {
        val columns=when { resources.configuration.fontScale>1.5f -> 1; values.size==3 && resources.configuration.screenWidthDp>=360 -> 3; else -> 2 }
        val grid=GridLayout(requireContext()).apply { columnCount=columns }
        parent.addView(grid,spaced(-1,-2))
        values.forEachIndexed { index,(label,value) ->
            val tone=tones[index%tones.size]
            val tile=MaterialCardView(requireContext()).apply {
                radius=dp(16).toFloat(); cardElevation=0f; strokeWidth=0; setCardBackgroundColor(context.usageColor(tone.container))
            }
            grid.addView(tile,GridLayout.LayoutParams(GridLayout.spec(index/columns),GridLayout.spec(index%columns,1f)).apply {
                width=0; height=-2; setGravity(Gravity.FILL); setMargins(dp(3),dp(3),dp(3),dp(3))
            })
            tile.addView(LinearLayout(requireContext()).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(12))
                addView(text(getString(label),tone=tone,appearance=com.google.android.material.R.style.TextAppearance_Material3_LabelMedium))
                addView(android.widget.Space(context),LinearLayout.LayoutParams(0,0,1f))
                addView(text(value,tone=tone,appearance=com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall).apply {
                    setTypeface(typeface,android.graphics.Typeface.BOLD)
                },spaced(-1,-2))
            })
        }
    }
    private fun row(parent: LinearLayout,label: String,value: String,color: Int?=null) {
        val row=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(7),0,dp(7)) }
        parent.addView(row,LinearLayout.LayoutParams(-1,-2))
        if(color!=null) row.addView(View(requireContext()).apply { background=rounded(color,4); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(8),dp(8)).apply { marginEnd=dp(8) })
        row.addView(text(label),LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(8) })
        row.addView(text(value).apply { setTypeface(typeface,android.graphics.Typeface.BOLD) },LinearLayout.LayoutParams(-2,-2))
    }
    private fun iconButton(iconRes: Int,label: Int,action: ()->Unit)=MaterialButton(requireContext(),null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        layoutParams=LinearLayout.LayoutParams(dp(48),dp(48)); icon=ContextCompat.getDrawable(context,iconRes)
        iconPadding=0; iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START; contentDescription=getString(label)
        setPadding(0,0,0,0); setOnClickListener { action() }
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
    private fun showPoint(point: UsagePoint) { Snackbar.make(binding.root,getString(R.string.usage_point_value,point.label,requireContext().actionCount(point.value)),Snackbar.LENGTH_SHORT).show() }
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
