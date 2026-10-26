package one.njk.sao.usage

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.TreeMap

data class UsageTotals(val actions: Long=0,val searches: Long=0,val copies: Long=0,val handoffs: Long=0,val activeDays: Int=0)

/** The sequence is prepared only when active dates change; advancing today is a constant-time lookup. */
internal data class UsageRuns(val best: Int=0,val trailing: Int=0,val last: Long?=null) {
    fun at(today: LocalDate)=UsageStreak(if(last==today.toEpochDay() || last==today.toEpochDay()-1) trailing else 0,best)
    companion object {
        fun from(sortedDates: List<Long>): UsageRuns {
            var best=0; var run=0; var previous: Long?=null
            sortedDates.forEach { date -> run=if(previous!=null && date==previous+1) run+1 else 1; best=maxOf(best,run); previous=date }
            return UsageRuns(best,run,previous)
        }
    }
}

/** Immutable prepared period. Neither date selection nor RecyclerView binding sums historical rows. */
class UsagePeriodSummary(val period: UsageSelection,val days: List<UsageDay>) {
    val dayCounts=days.associate { it.day to it.actions }
    val actions=days.sumOf { it.actions }
    val searches=days.sumOf { it.searches }
    val copies=days.sumOf { it.copies }
    val handoffs=days.sumOf { it.handoffs }
    val activeDays=days.size
    internal val runs=UsageRuns.from(days.map { it.day })
    internal val intensity=UsageIntensity(dayCounts.values)
    val daily=List(java.time.temporal.ChronoUnit.DAYS.between(period.start,period.endExclusive).toInt()) { dayCounts[period.start.plusDays(it.toLong()).toEpochDay()] ?: 0L }
    val bestDay=days.maxOfOrNull { it.actions } ?: 0L
    val bestWeek=days.groupBy { LocalDate.ofEpochDay(it.day).with(TemporalAdjusters.previousOrSame(period.firstWeekday)) }
        .values.maxOfOrNull { group -> group.sumOf { it.actions } } ?: 0L
    val bestMonth=days.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.day)) }.values.maxOfOrNull { group -> group.sumOf { it.actions } } ?: 0L
    companion object {
        val EMPTY=UsagePeriodSummary(UsageSelection(UsageRange.MONTH,LocalDate.of(1970,1,1),DayOfWeek.MONDAY),emptyList())
    }
}

data class UsageHistory(val days: List<UsageDay>,val today: LocalDate,val firstWeekday: DayOfWeek,
    val lifetime: UsageTotals,val streak: UsageStreak,
    val daySummaries: Map<Long,UsagePeriodSummary>,val weeks: Map<LocalDate,UsagePeriodSummary>,
    val months: Map<YearMonth,UsagePeriodSummary>,val years: Map<Int,UsagePeriodSummary>) {
    fun summary(period: UsageSelection): UsagePeriodSummary = when(period.range) {
        UsageRange.DAY -> daySummaries[period.start.toEpochDay()]
        UsageRange.WEEK -> weeks[period.start]
        UsageRange.MONTH -> months[YearMonth.from(period.start)]
        UsageRange.YEAR -> years[period.start.year]
    } ?: UsagePeriodSummary(period.copy(anchor=period.start),emptyList())
}

/** One CPU-confined index per ViewModel. Changed rows invalidate only their day/week/month/year. */
class UsageHistoryIndex {
    private val rows=TreeMap<Long,UsageDay>()
    private val days=mutableMapOf<Long,UsagePeriodSummary>()
    private val weeks=mutableMapOf<LocalDate,UsagePeriodSummary>()
    private val months=mutableMapOf<YearMonth,UsagePeriodSummary>()
    private val years=mutableMapOf<Int,UsagePeriodSummary>()
    private var totals=UsageTotals()
    private var runs=UsageRuns()
    private var previous: UsageHistory?=null

    fun update(incoming: List<UsageDay>,today: LocalDate,firstWeekday: DayOfWeek): UsageHistory {
        val next=incoming.filter { it.day<=today.toEpochDay() }.associateBy { it.day }
        val changed=(rows.keys+next.keys).filter { rows[it]!=next[it] }
        val changedDates=changed.any { rows.containsKey(it)!=next.containsKey(it) }
        val localeChanged=previous?.firstWeekday!=firstWeekday
        changed.forEach { day ->
            val before=rows[day]; val after=next[day]
            totals=UsageTotals(totals.actions+(after?.actions ?: 0)-(before?.actions ?: 0),
                totals.searches+(after?.searches ?: 0)-(before?.searches ?: 0),
                totals.copies+(after?.copies ?: 0)-(before?.copies ?: 0),
                totals.handoffs+(after?.handoffs ?: 0)-(before?.handoffs ?: 0))
            if(after==null) rows.remove(day) else rows[day]=after
        }
        totals=totals.copy(activeDays=rows.size)
        if(changedDates) runs=UsageRuns.from(rows.keys.toList())
        if(localeChanged) { days.clear(); weeks.clear(); months.clear(); years.clear() }
        val affected=if(localeChanged) rows.keys.toList() else changed
        fun prepare(range: UsageRange,date: LocalDate): UsagePeriodSummary? {
            val period=UsageSelection(range,date,firstWeekday)
            val entries=rows.subMap(period.start.toEpochDay(),true,period.endExclusive.toEpochDay(),false).values.toList()
            return if(entries.isEmpty()) null else UsagePeriodSummary(period.copy(anchor=period.start),entries)
        }
        affected.forEach { key ->
            prepare(UsageRange.DAY,LocalDate.ofEpochDay(key))?.let { days[key]=it } ?: days.remove(key)
        }
        affected.map { LocalDate.ofEpochDay(it).with(TemporalAdjusters.previousOrSame(firstWeekday)) }.toSet().forEach { date ->
            prepare(UsageRange.WEEK,date)?.let { weeks[date]=it } ?: weeks.remove(date)
        }
        affected.map { YearMonth.from(LocalDate.ofEpochDay(it)) }.toSet().forEach { month ->
            prepare(UsageRange.MONTH,month.atDay(1))?.let { months[month]=it } ?: months.remove(month)
        }
        affected.map { LocalDate.ofEpochDay(it).year }.toSet().forEach { year ->
            prepare(UsageRange.YEAR,LocalDate.of(year,1,1))?.let { years[year]=it } ?: years.remove(year)
        }
        val old=previous
        if(changed.isEmpty() && !localeChanged && old!=null) {
            if(old.today==today) return old
            return old.copy(today=today,streak=runs.at(today)).also { previous=it }
        }
        return UsageHistory(rows.values.toList(),today,firstWeekday,totals,runs.at(today),days.toMap(),weeks.toMap(),months.toMap(),years.toMap()).also { previous=it }
    }
}

data class UsageSearchHealth(val count: Long,val results: Long,val latencyMillis: Long,val modes: List<UsageShare<UsageSearchMode>>)

/** Prepared when a selected week's buckets change, and reused for every date in that week. */
class UsageDetails(val buckets: List<UsageBucket>) {
    val hourly=LongArray(24).also { counts -> buckets.forEach { if(it.hour in counts.indices) counts[it.hour]+=it.occurrences } }.toList()
    val targets=shares(buckets.filter { it.action.isHandoff && it.outcome==UsageOutcome.ACCEPTED }) { if(it.target==UsageTarget.NONE) UsageTarget.OTHER else it.target }
    val media=shares(buckets.filter { it.media!=UsageMedia.NONE }) { it.media }
    val sources=shares(buckets.filter { it.sourceId.isNotEmpty() }) { it.sourceId }
    val actions=shares(buckets) { it.action }
    val outcomes=shares(buckets.filter { it.action.isHandoff }) { it.outcome }
    val searchHealth=buckets.filter { it.action==UsageAction.SEARCH }.takeIf { it.isNotEmpty() }?.let { searches ->
        UsageSearchHealth(searches.sumOf { it.occurrences },searches.sumOf { it.results },searches.sumOf { it.latencyMillis },shares(searches) { it.searchMode })
    }
    companion object {
        val EMPTY=UsageDetails(emptyList())
        private fun <T> shares(rows: List<UsageBucket>,key: (UsageBucket)->T)=rows.groupBy(key)
            .map { (id,values) -> UsageShare(id,values.sumOf { it.occurrences }) }.sortedByDescending { it.count }
    }
}

class UsageWeekDetails(val period: UsageSelection,val buckets: List<UsageBucket>,val today: LocalDate) {
    val week=UsageDetails(buckets)
    private val grouped=buckets.groupBy { it.day }
    private val days=grouped.mapValues { UsageDetails(it.value) }
    private val totals=grouped.mapValues { (day,rows) ->
        rows.fold(UsageDay(day,0,0,0,0)) { sum,row -> row.dayDelta().let { delta ->
            UsageDay(day,sum.actions+delta.actions,sum.searches+delta.searches,sum.copies+delta.copies,sum.handoffs+delta.handoffs)
        } }
    }
    /** Independent Room flows may emit in either order; publish only a coherent transaction. */
    fun matches(history: UsageHistory): Boolean = (0L..6L).all { offset ->
        val day=period.start.plusDays(offset).toEpochDay()
        val actual=history.daySummaries[day]?.let { UsageDay(day,it.actions,it.searches,it.copies,it.handoffs) }
        actual==totals[day]
    }
    fun details(selection: UsageSelection)=when(selection.range) {
        UsageRange.DAY -> days[selection.start.toEpochDay()] ?: UsageDetails.EMPTY
        UsageRange.WEEK -> week
        else -> UsageDetails.EMPTY
    }
}
