package one.njk.sao.usage

import one.njk.sao.source.MediaItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class UsageAction {
    SEARCH, COPY_MEDIA, COPY_FILENAME, PASTE, SHARE, STICKER, DOWNLOAD, BOOKMARK;
    val isHandoff get()=this==PASTE || this==SHARE || this==STICKER
}
enum class UsageSurface { HOME, PICKER }
enum class UsageMedia { NONE, IMAGE, ANIMATION, VIDEO }
enum class UsageTarget { NONE, WHATSAPP, TELEGRAM, DISCORD, INSTAGRAM, OTHER }
enum class UsageRoute { NONE, CLIPBOARD, ACCESSIBILITY, NATIVE, ATTACHMENT }
enum class UsageOutcome { ACCEPTED, FAILED, UNCERTAIN }
enum class UsageSearchMode { NONE, CONTEXT, FILENAME, DESCRIPTION, PATH, OCR, RANDOM }
enum class UsageRange { DAY, WEEK, MONTH, YEAR }
enum class UsageCalendarMode(val range: UsageRange) { MONTH(UsageRange.MONTH), YEAR(UsageRange.YEAR) }

/** Aggregate dimensions deliberately cannot contain queries, media identities or chat metadata. */
data class UsageEvent(
    val action: UsageAction,
    val surface: UsageSurface,
    val sourceId: String = "",
    val media: UsageMedia = UsageMedia.NONE,
    val target: UsageTarget = UsageTarget.NONE,
    val route: UsageRoute = UsageRoute.NONE,
    val outcome: UsageOutcome = UsageOutcome.ACCEPTED,
    val searchMode: UsageSearchMode = UsageSearchMode.NONE,
    val results: Long = 0,
    val latencyMillis: Long = 0,
) {
    internal fun bucket(at: LocalDateTime): UsageBucket = UsageBucket(
        at.toLocalDate().toEpochDay(),at.hour,action,surface,profileId(sourceId),media,target,route,outcome,
        searchMode,
        results=results.coerceAtLeast(0),latencyMillis=latencyMillis.coerceAtLeast(0))

    companion object {
        fun profileId(identity: String): String = runCatching {
            UUID.fromString(identity.substringBefore(':')).toString()
        }.getOrDefault("")
        fun media(item: MediaItem) = when { item.isVideo -> UsageMedia.VIDEO; item.animated -> UsageMedia.ANIMATION; else -> UsageMedia.IMAGE }
    }
}

data class UsageSelection(val range: UsageRange, val anchor: LocalDate, val firstWeekday: java.time.DayOfWeek) {
    val start: LocalDate get() = when(range) {
        UsageRange.DAY -> anchor
        UsageRange.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(firstWeekday))
        UsageRange.MONTH -> anchor.withDayOfMonth(1)
        UsageRange.YEAR -> anchor.withDayOfYear(1)
    }
    val endExclusive: LocalDate get() = when(range) {
        UsageRange.DAY -> start.plusDays(1)
        UsageRange.WEEK -> start.plusWeeks(1)
        UsageRange.MONTH -> start.plusMonths(1)
        UsageRange.YEAR -> start.plusYears(1)
    }
}

/** One date selects already prepared history and a single loaded week's details. */
data class UsageDashboard(val selection: UsageSelection,val weekBuckets: List<UsageBucket>,val days: List<UsageDay>,val today: LocalDate,
    private val preparedHistory: UsageHistory?=null,private val preparedWeek: UsageWeekDetails?=null) {
    private val history=preparedHistory?.takeIf { it.today==today && it.firstWeekday==selection.firstWeekday && it.days===days }
        ?: UsageHistoryIndex().update(days,today,selection.firstWeekday)
    private val weekPeriod=selection.copy(range=UsageRange.WEEK)
    private val details=preparedWeek?.takeIf { it.today==today && it.period.start==weekPeriod.start && it.period.endExclusive==weekPeriod.endExclusive && it.buckets===weekBuckets }
        ?: UsageWeekDetails(weekPeriod,weekBuckets.filter { it.day>=weekPeriod.start.toEpochDay() && it.day<weekPeriod.endExclusive.toEpochDay() && it.day<=today.toEpochDay() },today)
    val calendarMonths=history.months
    val lifetime=history.lifetime
    private fun report(range: UsageRange): UsageReport {
        val period=selection.copy(range=range)
        val breakdown=details.details(period)
        return UsageReport(period,breakdown.buckets,history.days,today,history,breakdown)
    }
    val day=report(UsageRange.DAY)
    val week=report(UsageRange.WEEK)
    val month=report(UsageRange.MONTH)
    val year=report(UsageRange.YEAR)
}

/** Relative quartiles keep light/heavy days distinguishable even when a single day is an outlier. */
internal class UsageIntensity(counts: Collection<Long>) {
    private val active=counts.filter { it>0 }.sorted()
    private fun threshold(quarter: Int)=active[(active.size*quarter/4).coerceAtMost(active.lastIndex)]
    fun level(count: Long): Int=when {
        count<=0 || active.isEmpty() -> 0
        count>=threshold(3) -> 4
        count>=threshold(2) -> 3
        count>=threshold(1) -> 2
        else -> 1
    }
}

data class UsageShare<T>(val key: T, val count: Long)
data class UsageStreak(val current: Int = 0, val best: Int = 0)

data class UsageReport(
    val selection: UsageSelection,
    val buckets: List<UsageBucket>,
    val days: List<UsageDay>,
    val today: LocalDate,
    private val preparedHistory: UsageHistory?=null,
    private val preparedDetails: UsageDetails?=null,
) {
    private val history=preparedHistory?.takeIf { it.today==today && it.firstWeekday==selection.firstWeekday && it.days===days }
        ?: UsageHistoryIndex().update(days,today,selection.firstWeekday)
    private val summary=history.summary(selection)
    private val details=preparedDetails ?: UsageDetails(buckets.filter { it.day>=selection.start.toEpochDay() && it.day<selection.endExclusive.toEpochDay() && it.day<=today.toEpochDay() })
    val periodDays=summary.days
    val dayCounts=summary.dayCounts
    internal val intensity=summary.intensity
    val actions=summary.actions
    val searches=summary.searches
    val copies=summary.copies
    val handoffs=summary.handoffs
    val activeDays=summary.activeDays
    val streak=history.streak
    val periodStreak=summary.runs.at(today)
    val hourly=details.hourly
    val daily=summary.daily
    val targets=details.targets
    val media=details.media
    val sources=details.sources
    val actionCounts=details.actions
    val outcomes=details.outcomes
    val searchHealth=details.searchHealth
    val bestDay=summary.bestDay
    val bestWeek=summary.bestWeek
    val bestMonth=summary.bestMonth
    companion object {
        fun streak(input: List<Long>,today: LocalDate)=UsageRuns.from(input.distinct().filter { it<=today.toEpochDay() }.sorted()).at(today)
    }
}
