package one.njk.sao.usage

import one.njk.sao.source.MediaItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class UsageAction { SEARCH, COPY_MEDIA, COPY_FILENAME, PASTE, SHARE, STICKER, DOWNLOAD, BOOKMARK }
enum class UsageSurface { HOME, PICKER }
enum class UsageMedia { NONE, IMAGE, ANIMATION, VIDEO }
enum class UsageTarget { NONE, WHATSAPP, TELEGRAM, DISCORD, INSTAGRAM, OTHER }
enum class UsageRoute { NONE, CLIPBOARD, ACCESSIBILITY, NATIVE, ATTACHMENT }
enum class UsageOutcome { ACCEPTED, FAILED, UNCERTAIN }
enum class UsageSearchMode { NONE, CONTEXT, FILENAME, DESCRIPTION, PATH, OCR, RANDOM }
enum class UsagePage { OVERVIEW, DAY, WEEK, YEAR }

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

data class UsageSelection(val page: UsagePage, val anchor: LocalDate, val firstWeekday: java.time.DayOfWeek) {
    val start: LocalDate get() = when(page) {
        UsagePage.OVERVIEW -> anchor.withDayOfMonth(1)
        UsagePage.DAY -> anchor
        UsagePage.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(firstWeekday))
        UsagePage.YEAR -> anchor.withDayOfYear(1)
    }
    val endExclusive: LocalDate get() = when(page) {
        UsagePage.OVERVIEW -> start.plusMonths(1)
        UsagePage.DAY -> start.plusDays(1)
        UsagePage.WEEK -> start.plusWeeks(1)
        UsagePage.YEAR -> start.plusYears(1)
    }
}

data class UsageDay(val day: Long, val actions: Long, val searches: Long, val copies: Long, val handoffs: Long)
data class UsageShare<T>(val key: T, val count: Long)
data class UsageStreak(val current: Int = 0, val best: Int = 0)

data class UsageReport(
    val selection: UsageSelection,
    val buckets: List<UsageBucket>,
    val days: List<UsageDay>,
    val today: LocalDate,
) {
    val periodDays = days.filter { it.day >= selection.start.toEpochDay() && it.day < selection.endExclusive.toEpochDay() && it.day<=today.toEpochDay() }
    val actions = periodDays.sumOf { it.actions }
    val searches = periodDays.sumOf { it.searches }
    val copies = periodDays.sumOf { it.copies }
    val handoffs = periodDays.sumOf { it.handoffs }
    val activeDays = periodDays.size
    val streak = streak(days.map { it.day },today)
    val periodStreak = streak(periodDays.map { it.day },today)
    val hourly = List(24) { hour -> buckets.filter { it.hour==hour }.sumOf { it.occurrences } }
    val daily = (0 until java.time.temporal.ChronoUnit.DAYS.between(selection.start,selection.endExclusive).toInt()).map { offset ->
        val day=selection.start.plusDays(offset.toLong()).toEpochDay()
        periodDays.firstOrNull { it.day==day }?.actions ?: 0L
    }
    val targets = shares(buckets.filter { it.action in handoffActions && it.outcome==UsageOutcome.ACCEPTED }) { if(it.target==UsageTarget.NONE) UsageTarget.OTHER else it.target }
    val media = shares(buckets.filter { it.media!=UsageMedia.NONE }) { it.media }
    val sources = shares(buckets.filter { it.sourceId.isNotEmpty() }) { it.sourceId }
    val actionCounts = shares(buckets) { it.action }
    val outcomes = shares(buckets.filter { it.action in handoffActions }) { it.outcome }
    val bestDay = periodDays.maxOfOrNull { it.actions } ?: 0
    val bestWeek = periodDays.groupBy { LocalDate.ofEpochDay(it.day).with(TemporalAdjusters.previousOrSame(selection.firstWeekday)) }
        .values.maxOfOrNull { group -> group.sumOf { it.actions } } ?: 0
    val bestMonth = periodDays.groupBy { LocalDate.ofEpochDay(it.day).month }.values.maxOfOrNull { group -> group.sumOf { it.actions } } ?: 0

    companion object {
        val handoffActions = setOf(UsageAction.PASTE,UsageAction.SHARE,UsageAction.STICKER)
        fun streak(input: List<Long>,today: LocalDate): UsageStreak {
            val sorted=input.distinct().filter { it<=today.toEpochDay() }.sorted()
            var best=0; var run=0; var previous: Long?=null
            sorted.forEach { day -> run=if(previous!=null && day==previous!!+1) run+1 else 1; best=maxOf(best,run); previous=day }
            val current=if(sorted.lastOrNull() in listOf(today.toEpochDay(),today.toEpochDay()-1)) run else 0
            return UsageStreak(current,best)
        }
        private fun <T> shares(rows: List<UsageBucket>,key: (UsageBucket)->T) = rows.groupBy(key)
            .map { (id,values) -> UsageShare(id,values.sumOf { it.occurrences }) }.sortedByDescending { it.count }
    }
}
