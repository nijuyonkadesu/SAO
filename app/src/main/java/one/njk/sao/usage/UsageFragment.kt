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
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.FragmentUsageBinding
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@AndroidEntryPoint
class UsageFragment: Fragment() {
    private var _binding: FragmentUsageBinding?=null
    private val binding get()=requireNotNull(_binding)
    private val model: UsageViewModel by viewModels()
    private var rendered: UsageReport?=null
    private var names: Map<String,String> = emptyMap()
    private var syncing=false
    private val locale get()=resources.configuration.locales[0]
    private val numbers get()=NumberFormat.getIntegerInstance(locale)
    private fun dp(value: Int)=(resources.displayMetrics.density*value).toInt()
    private val datesChanged=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) { model.refresh(locale) }
    }
    override fun onCreateView(inflater: LayoutInflater,container: ViewGroup?,state: Bundle?): View {
        _binding=FragmentUsageBinding.inflate(inflater,container,false)
        return binding.root
    }
    override fun onViewCreated(view: View,savedInstanceState: Bundle?) {
        binding.usageTabs.apply {
            if(resources.configuration.fontScale>1.3f) tabMode=TabLayout.MODE_SCROLLABLE
            listOf(R.string.usage_overview,R.string.usage_day,R.string.usage_week,R.string.usage_year).forEach { addTab(newTab().setText(it)) }
            getTabAt(model.selectionState.value.page.ordinal)?.select()
            addOnTabSelectedListener(object: TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if(syncing) return
                    rendered?.let { model.scroll(it.selection.page,binding.usageScroll.scrollY) }
                    model.page(UsagePage.entries[tab.position])
                }
                override fun onTabUnselected(tab: TabLayout.Tab)=Unit
                override fun onTabReselected(tab: TabLayout.Tab)=Unit
            })
        }
        binding.usagePrevious.setOnClickListener { model.step(-1) }
        binding.usageNext.setOnClickListener { model.step(1) }
        binding.usagePeriod.setOnClickListener { chooseDate() }
        binding.usageRecording.setOnClickListener { if(model.state.value.enabled!=null) model.enabled(binding.usageRecording.isChecked) }
        binding.usageClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.usage_clear_title).setMessage(R.string.usage_clear_message)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.usage_clear) { _,_ -> model.clear() }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.selectionState.collect { selected ->
                syncing=true
                binding.usageTabs.getTabAt(selected.page.ordinal)?.select()
                syncing=false
                binding.usagePeriod.text=period(selected)
                binding.usagePeriod.contentDescription=getString(R.string.usage_date_selection,period(selected))
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
        rendered?.let { model.scroll(it.selection.page,binding.usageScroll.scrollY) }
        rendered=null; _binding=null; super.onDestroyView()
    }
    private fun render(report: UsageReport) {
        val old=rendered
        val position=if(old?.selection?.page==report.selection.page) binding.usageScroll.scrollY else model.scroll(report.selection.page)
        rendered=report
        binding.usageNext.isEnabled=report.selection.endExclusive<=report.today
        binding.usageContent.removeAllViews()
        if(report.days.isEmpty()) section(R.string.usage_empty_title).addView(text(getString(R.string.usage_empty)))
        when(report.selection.page) {
            UsagePage.OVERVIEW -> overview(report)
            UsagePage.DAY -> day(report)
            UsagePage.WEEK -> week(report)
            UsagePage.YEAR -> year(report)
        }
        val current=binding
        current.usageScroll.post {
            if(_binding===current && rendered?.selection==report.selection) current.usageScroll.scrollTo(0,position)
        }
    }
    private fun overview(report: UsageReport) {
        val today=report.days.firstOrNull { it.day==report.today.toEpochDay() }
        metrics(section(R.string.usage_today),listOf(R.string.usage_searches to (today?.searches ?: 0),
            R.string.usage_copies to (today?.copies ?: 0),R.string.usage_handoffs to (today?.handoffs ?: 0),R.string.usage_actions to (today?.actions ?: 0)))
        metricsText(section(R.string.usage_streaks),listOf(R.string.usage_current_streak to dayCount(report.streak.current),R.string.usage_best_streak to dayCount(report.streak.best)))
        section(R.string.usage_calendar).apply {
            addView(UsageMonthView(context,YearMonth.from(report.selection.anchor),report.days.associate { it.day to it.actions },report.today,report.selection.firstWeekday,model::openDay),spaced(-1,-2))
            metricsText(this,listOf(R.string.usage_active_days to numbers.format(report.activeDays),
                R.string.usage_average_day to NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits=1 }.format(if(report.activeDays==0) 0.0 else report.actions.toDouble()/report.activeDays),
                R.string.usage_actions to numbers.format(report.actions)))
        }
        metrics(section(R.string.usage_lifetime),listOf(R.string.usage_actions to report.days.sumOf { it.actions },
            R.string.usage_active_days to report.days.size.toLong(),R.string.usage_searches to report.days.sumOf { it.searches },R.string.usage_copies to report.days.sumOf { it.copies }))
    }
    private fun day(report: UsageReport) {
        summary(report,R.string.usage_selected_day)
        val points=report.hourly.mapIndexed { hour,value -> UsagePoint(hourLabel(hour),value) }
        section(R.string.usage_hourly).addView(UsageTrendView(requireContext(),points) { index ->
            showPoint(points[index])
        },spaced(-1,dp(220)))
        section(R.string.usage_hourly_summary).apply {
            val used=points.withIndex().filter { it.value.value>0 }
            if(used.isEmpty()) addView(text(getString(R.string.usage_empty_period)))
            used.forEach { (index,point) -> row(this,getString(R.string.usage_hour_range,point.label,hourLabel((index+1)%24)),requireContext().actionCount(point.value)) }
        }
        breakdown(report)
    }
    private fun week(report: UsageReport) {
        summary(report,R.string.usage_selected_week)
        section(R.string.usage_destinations).apply {
            addView(text(getString(R.string.usage_destination_note)),spaced(-1,-2))
            val points=report.targets.map { UsagePoint(target(it.key),it.count) }
            addView(UsageDonutView(context,points),spaced(-1,dp(220)))
            if(points.isEmpty()) addView(text(getString(R.string.usage_empty_period)))
            val total=points.sumOf { it.value }
            val percent=NumberFormat.getPercentInstance(locale)
            points.forEachIndexed { index,point -> row(this,point.label,
                getString(R.string.usage_percent_count,numbers.format(point.value),percent.format(point.value.toDouble()/total)),context.usageSeriesColors()[index%4]) }
        }
        val points=report.daily.mapIndexed { index,value -> UsagePoint(report.selection.start.plusDays(index.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT,locale),value) }
        section(R.string.usage_daily_trend).addView(UsageTrendView(requireContext(),points,true) { index ->
            model.openDay(report.selection.start.plusDays(index.toLong()))
        },spaced(-1,dp(220)))
        breakdown(report)
    }
    private fun year(report: UsageReport) {
        summary(report,R.string.usage_selected_year)
        metricsText(section(R.string.usage_streaks),listOf(R.string.usage_active_days to dayCount(report.activeDays),R.string.usage_best_streak to dayCount(report.periodStreak.best)))
        metrics(section(R.string.usage_busiest),listOf(R.string.usage_best_day to report.bestDay,R.string.usage_best_week to report.bestWeek,R.string.usage_best_month to report.bestMonth))
        section(R.string.usage_heatmap).apply {
            addView(text(getString(R.string.usage_heatmap_hint)),spaced(-1,-2))
            var monthWidth=dp(344)
            val list=object: RecyclerView(context) {
                override fun onMeasure(widthSpec: Int,heightSpec: Int) {
                    monthWidth=View.MeasureSpec.getSize(widthSpec)
                    super.onMeasure(widthSpec,heightSpec)
                }
            }.apply {
                layoutManager=LinearLayoutManager(context,RecyclerView.HORIZONTAL,false)
                isNestedScrollingEnabled=false
                adapter=object: RecyclerView.Adapter<MonthHolder>() {
                    override fun getItemCount()=12
                    override fun onCreateViewHolder(parent: ViewGroup,type: Int)=MonthHolder(LinearLayout(parent.context).apply {
                        orientation=LinearLayout.VERTICAL
                        layoutParams=RecyclerView.LayoutParams(monthWidth,-2)
                    })
                    override fun onBindViewHolder(holder: MonthHolder,position: Int) {
                        val month=YearMonth.of(report.selection.anchor.year,position+1)
                        holder.column.removeAllViews()
                        holder.column.addView(text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy",locale)),title=true))
                        holder.column.addView(UsageMonthView(context,month,report.periodDays.associate { it.day to it.actions },report.today,report.selection.firstWeekday,model::openDay),spaced(-1,-2))
                    }
                }
                addOnScrollListener(object: RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView,state: Int) {
                        if(state==RecyclerView.SCROLL_STATE_IDLE && recyclerView.isAttachedToWindow) {
                            val position=(layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                            if(position>=0) model.heatmapMonth=position
                        }
                    }
                })
            }
            addView(list,spaced(-1,-2))
            PagerSnapHelper().attachToRecyclerView(list)
            list.scrollToPosition(model.heatmapMonth)
        }
    }
    private class MonthHolder(val column: LinearLayout): RecyclerView.ViewHolder(column)
    private fun summary(report: UsageReport,title: Int) {
        metrics(section(title),listOf(R.string.usage_actions to report.actions,R.string.usage_searches to report.searches,
            R.string.usage_copies to report.copies,R.string.usage_handoffs to report.handoffs))
    }
    private fun breakdown(report: UsageReport) {
        val searches=report.buckets.filter { it.action==UsageAction.SEARCH }
        if(searches.isNotEmpty()) {
            val count=searches.sumOf { it.occurrences }
            metricsText(section(R.string.usage_search_health),listOf(
                R.string.usage_search_results to numbers.format(searches.sumOf { it.results }),
                R.string.usage_search_latency to getString(R.string.usage_milliseconds,numbers.format(searches.sumOf { it.latencyMillis }/count))))
            section(R.string.usage_search_modes).apply {
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
        section(R.string.usage_action_breakdown).apply {
            if(report.actionCounts.isEmpty()) addView(text(getString(R.string.usage_empty_period)))
            report.actionCounts.forEach { row(this,getString(actionLabel(it.key)),numbers.format(it.count)) }
        }
        if(report.media.isNotEmpty()) section(R.string.usage_media).apply {
            report.media.forEach { row(this,getString(when(it.key) {
                UsageMedia.VIDEO -> R.string.usage_video; UsageMedia.ANIMATION -> R.string.usage_animation
                else -> R.string.usage_image
            }),numbers.format(it.count)) }
        }
        if(report.sources.isNotEmpty()) section(R.string.usage_sources).apply {
            report.sources.forEach { row(this,names[it.key]?.let { name -> one.njk.sao.floating.sourceDisplayName(requireContext(),it.key,name) } ?: getString(R.string.usage_removed_source),numbers.format(it.count)) }
        }
        if(report.outcomes.isNotEmpty()) section(R.string.usage_outcomes).apply {
            report.outcomes.forEach { row(this,getString(when(it.key) {
                UsageOutcome.ACCEPTED -> R.string.usage_accepted; UsageOutcome.FAILED -> R.string.usage_failed; UsageOutcome.UNCERTAIN -> R.string.usage_uncertain
            }),numbers.format(it.count)) }
        }
    }
    private fun actionLabel(action: UsageAction)=when(action) {
        UsageAction.SEARCH -> R.string.usage_searches; UsageAction.COPY_MEDIA -> R.string.usage_copies
        UsageAction.COPY_FILENAME -> R.string.usage_filename; UsageAction.PASTE -> R.string.usage_pastes
        UsageAction.SHARE -> R.string.usage_share; UsageAction.STICKER -> R.string.usage_stickers
        UsageAction.DOWNLOAD -> R.string.usage_downloads; UsageAction.BOOKMARK -> R.string.usage_bookmarks
    }
    private fun target(target: UsageTarget)=getString(when(target) {
        UsageTarget.WHATSAPP -> R.string.usage_whatsapp; UsageTarget.TELEGRAM -> R.string.usage_telegram
        UsageTarget.DISCORD -> R.string.usage_discord; UsageTarget.INSTAGRAM -> R.string.usage_instagram
        else -> R.string.usage_other_app
    })
    private fun section(title: Int): LinearLayout {
        val context=requireContext()
        val card=MaterialCardView(context).apply {
            radius=dp(24).toFloat(); cardElevation=0f; strokeWidth=0
            setCardBackgroundColor(context.usageColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        binding.usageContent.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val column=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        card.addView(column)
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        column.addView(header,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val badge=FrameLayout(context).apply {
            background=MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(18).toFloat()).build()).apply {
                fillColor=ColorStateList.valueOf(context.usageColor(com.google.android.material.R.attr.colorPrimaryContainer))
            }
        }
        header.addView(badge,LinearLayout.LayoutParams(dp(36),dp(36)).apply { marginEnd=dp(10) })
        badge.addView(ImageView(context).apply {
            setImageResource(if(title==R.string.usage_calendar || title==R.string.usage_heatmap) R.drawable.ic_usage_calendar else R.drawable.ic_usage)
            imageTintList=ColorStateList.valueOf(context.usageColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))
        header.addView(text(getString(title),true),LinearLayout.LayoutParams(0,-2,1f))
        return column
    }
    private fun text(value: String,title: Boolean=false)=MaterialTextView(requireContext()).apply {
        text=value
        setTextAppearance(if(title) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(context.usageColor(if(title) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
        if(title) ViewCompat.setAccessibilityHeading(this,true)
    }
    private fun metrics(parent: LinearLayout,values: List<Pair<Int,Long>>) = metricsText(parent,values.map { it.first to numbers.format(it.second) })
    private fun metricsText(parent: LinearLayout,values: List<Pair<Int,String>>) {
        val columns=when { resources.configuration.fontScale>1.5f -> 1; values.size==3 && resources.configuration.screenWidthDp>=380 -> 3; else -> 2 }
        val grid=GridLayout(requireContext()).apply { columnCount=columns }
        parent.addView(grid,spaced(-1,-2))
        values.forEachIndexed { index,(label,value) ->
            val tile=MaterialCardView(requireContext()).apply {
                radius=dp(16).toFloat(); cardElevation=0f; strokeWidth=0
                setCardBackgroundColor(context.usageColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
            grid.addView(tile,GridLayout.LayoutParams(GridLayout.spec(index/columns),GridLayout.spec(index%columns,1f)).apply {
                width=0; height=-2; setGravity(Gravity.FILL); setMargins(dp(3),dp(3),dp(3),dp(3))
            })
            tile.addView(LinearLayout(requireContext()).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(14))
                addView(text(getString(label)))
                addView(android.widget.Space(context),LinearLayout.LayoutParams(0,0,1f))
                addView(text(value).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
                    setTextColor(context.usageColor(androidx.appcompat.R.attr.colorPrimary))
                    setTypeface(typeface,android.graphics.Typeface.BOLD)
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,18,28,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                },spaced(-1,-2))
            })
        }
    }
    private fun row(parent: LinearLayout,label: String,value: String,color: Int?=null) {
        val row=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(8),0,dp(8)) }
        parent.addView(row,LinearLayout.LayoutParams(-1,-2))
        if(color!=null) row.addView(View(requireContext()).apply {
            background=MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(5).toFloat()).build()).apply { fillColor=ColorStateList.valueOf(color) }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(10),dp(10)).apply { marginEnd=dp(10) })
        row.addView(text(label),LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(12) })
        row.addView(text(value,true),LinearLayout.LayoutParams(-2,-2))
    }
    private fun spaced(width: Int,height: Int)=LinearLayout.LayoutParams(width,height).apply { topMargin=dp(8) }
    private fun hourLabel(hour: Int)=java.time.LocalTime.of(hour,0).format(DateTimeFormatter.ofPattern("HH:mm",locale).withDecimalStyle(java.time.format.DecimalStyle.of(locale)))
    private fun dayCount(value: Int)=resources.getQuantityString(R.plurals.usage_days_count,value,numbers.format(value))
    private fun period(selection: UsageSelection): String = when(selection.page) {
        UsagePage.OVERVIEW -> selection.start.format(DateTimeFormatter.ofPattern("LLLL yyyy",locale))
        UsagePage.DAY -> selection.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        UsagePage.WEEK -> getString(R.string.usage_week_range,selection.start.format(DateTimeFormatter.ofPattern("MMM d",locale)),selection.endExclusive.minusDays(1).format(DateTimeFormatter.ofPattern("MMM d, yyyy",locale)))
        UsagePage.YEAR -> NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed=false }.format(selection.start.year)
    }
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
}
