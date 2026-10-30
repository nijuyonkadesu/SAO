package one.njk.sao.usage

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate
import java.time.YearMonth

/** Only visible report sections create Views. Each section has its own immutable invalidation key. */
internal class UsageSections(private val bind: (Kind,LinearLayout,Page)->Unit): RecyclerView.Adapter<UsageSections.Holder>() {
    enum class Kind { HERO,CALENDAR,HOURS,WEEK,BREAKDOWN,RECORDS,PRIVACY }
    data class Page(val report: UsageDashboard?,val mode: UsageCalendarMode,val names: Map<String,String>,val enabled: Boolean?)
    internal sealed interface Content {
        data class Hero(val date: LocalDate,val today: LocalDate,val totals: UsagePeriodSummary,val empty: Boolean): Content
        data class Calendar(val date: LocalDate,val today: LocalDate,val mode: UsageCalendarMode,
            val year: UsagePeriodSummary?,val months: Map<YearMonth,UsagePeriodSummary>): Content
        data class Hours(val date: LocalDate,val details: UsageDetails): Content
        data class Week(val start: LocalDate,val totals: List<UsageDay>,val details: UsageDetails): Content
        data class Breakdown(val details: UsageDetails,val names: Map<String,String>): Content
        data class Records(val period: UsageSelection,val totals: List<UsageDay>,val streak: UsageStreak,val lifetime: UsageTotals): Content
        data class Privacy(val enabled: Boolean?,val ready: Boolean): Content
    }
    internal data class Row(val kind: Kind,val content: Content)
    private var page=Page(null,UsageCalendarMode.YEAR,emptyMap(),null)
    private var rows=rows(page)
    init { setHasStableIds(true); stateRestorationPolicy=StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    class Holder(val column: LinearLayout): RecyclerView.ViewHolder(column) { internal var bound: Row?=null }
    override fun getItemCount()=rows.size
    override fun getItemId(position: Int)=rows[position].kind.ordinal.toLong()
    override fun getItemViewType(position: Int)=rows[position].kind.ordinal
    override fun onCreateViewHolder(parent: ViewGroup,viewType: Int)=Holder(LinearLayout(parent.context).apply {
        orientation=LinearLayout.VERTICAL; layoutParams=RecyclerView.LayoutParams(-1,-2)
    })
    override fun onBindViewHolder(holder: Holder,position: Int) {
        val row=rows[position]
        if(holder.bound!=row) { bind(row.kind,holder.column,page); holder.bound=row }
    }
    fun submit(next: Page) {
        val old=rows; val updated=rows(next)
        page=next; rows=updated
        DiffUtil.calculateDiff(object: DiffUtil.Callback() {
            override fun getOldListSize()=old.size
            override fun getNewListSize()=updated.size
            override fun areItemsTheSame(a: Int,b: Int)=old[a].kind==updated[b].kind
            override fun areContentsTheSame(a: Int,b: Int)=old[a]==updated[b]
        },false).dispatchUpdatesTo(this)
    }
    fun position(kind: Kind)=rows.indexOfFirst { it.kind==kind }
    private fun rows(page: Page): List<Row> = buildList {
        if(page.report==null) return@buildList
        page.report?.let { report ->
            add(Row(Kind.HERO,Content.Hero(report.selection.anchor,report.today,report.day.summary,report.days.isEmpty())))
            add(Row(Kind.CALENDAR,Content.Calendar(report.selection.anchor,report.today,page.mode,
                report.year.summary.takeIf { page.mode==UsageCalendarMode.YEAR },
                if(page.mode==UsageCalendarMode.MONTH) report.calendarMonths else emptyMap())))
            add(Row(Kind.HOURS,Content.Hours(report.selection.anchor,report.day.details)))
            add(Row(Kind.WEEK,Content.Week(report.week.selection.start,report.week.periodDays,report.week.details)))
            add(Row(Kind.BREAKDOWN,Content.Breakdown(report.week.details,page.names)))
            add(Row(Kind.RECORDS,Content.Records(report.year.selection.copy(anchor=report.year.selection.start),report.year.periodDays,report.year.periodStreak,report.lifetime)))
        }
        add(Row(Kind.PRIVACY,Content.Privacy(page.enabled,page.report!=null)))
    }
}
