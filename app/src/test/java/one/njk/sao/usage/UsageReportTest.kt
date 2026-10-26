package one.njk.sao.usage

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class UsageReportTest {
    private val today=LocalDate.of(2026,10,26)
    private fun day(date: String,actions: Long=1)=UsageDay(LocalDate.parse(date).toEpochDay(),actions,0,0,0)
    @Test fun emptyHistoryHasNoArtificialRecordsOrStreak() {
        val report=UsageReport(UsageSelection(UsagePage.WEEK,today,DayOfWeek.MONDAY),emptyList(),emptyList(),today)
        assertEquals(0L,report.actions); assertEquals(0,report.activeDays); assertEquals(UsageStreak(),report.streak)
        assertEquals(List(7) { 0L },report.daily); assertEquals(List(24) { 0L },report.hourly)
    }
    @Test fun gapsYesterdayAndFutureAreHandledWithoutExtendingStreaks() {
        val days=listOf("2026-10-19","2026-10-20","2026-10-21","2026-10-24","2026-10-25","2026-10-28").map { LocalDate.parse(it).toEpochDay() }
        assertEquals(UsageStreak(2,3),UsageReport.streak(days,today))
        assertEquals(UsageStreak(0,3),UsageReport.streak(days,today.plusDays(4)))
        assertEquals(UsageStreak(3,3),UsageReport.streak(days+today.toEpochDay(),today))
    }
    @Test fun weekBoundariesFollowLocaleAndIncludeAdjacentYearDates() {
        val date=LocalDate.of(2027,1,1)
        val monday=UsageSelection(UsagePage.WEEK,date,DayOfWeek.MONDAY)
        val sunday=monday.copy(firstWeekday=DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026,12,28),monday.start)
        assertEquals(LocalDate.of(2027,1,4),monday.endExclusive)
        assertEquals(LocalDate.of(2026,12,27),sunday.start)
        val report=UsageReport(monday,emptyList(),listOf(day("2026-12-28",3),day("2027-01-01",7),day("2027-01-04",99)),date.plusDays(4))
        assertEquals(10L,report.actions); assertEquals(listOf(3L,0,0,0,7,0,0),report.daily)
    }
    @Test fun leapYearAndAnnualRecordsAreLimitedToSelectedYear() {
        val selection=UsageSelection(UsagePage.YEAR,LocalDate.of(2024,2,29),DayOfWeek.MONDAY)
        val report=UsageReport(selection,emptyList(),listOf(day("2023-12-31",999),day("2024-02-28",4),day("2024-02-29",6),day("2024-03-01",10),day("2025-01-01",99)),today)
        assertEquals(366,report.daily.size); assertEquals(20L,report.actions)
        assertEquals(3,report.periodStreak.best); assertEquals(10L,report.bestDay)
        assertEquals(20L,report.bestWeek); assertEquals(10L,report.bestMonth)
    }
    @Test fun destinationSharesCountOnlyAcceptedHandoffs() {
        val rows=listOf(
            UsageBucket(today.toEpochDay(),12,UsageAction.PASTE,UsageSurface.PICKER,target=UsageTarget.TELEGRAM,occurrences=3),
            UsageBucket(today.toEpochDay(),12,UsageAction.PASTE,UsageSurface.PICKER,target=UsageTarget.DISCORD,outcome=UsageOutcome.FAILED,occurrences=9),
            UsageBucket(today.toEpochDay(),12,UsageAction.PASTE,UsageSurface.PICKER,target=UsageTarget.INSTAGRAM,outcome=UsageOutcome.UNCERTAIN,occurrences=7),
            UsageBucket(today.toEpochDay(),12,UsageAction.COPY_MEDIA,UsageSurface.PICKER,target=UsageTarget.DISCORD,occurrences=2),
            UsageBucket(today.toEpochDay(),12,UsageAction.SHARE,UsageSurface.HOME,target=UsageTarget.WHATSAPP,occurrences=1))
        val report=UsageReport(UsageSelection(UsagePage.DAY,today,DayOfWeek.MONDAY),rows,emptyList(),today)
        assertEquals(listOf(UsageShare(UsageTarget.TELEGRAM,3),UsageShare(UsageTarget.WHATSAPP,1)),report.targets)
        assertEquals(20L,report.outcomes.sumOf { it.count })
        assertEquals(22L,report.hourly[12])
    }
    @Test fun sourceConfigurationAndRawSearchDataCannotBecomeDimensions() {
        val uuid="f7a936dd-f5bc-4e99-a344-bd6a394f1788"
        assertEquals(uuid,UsageEvent.profileId("$uuid:private-configuration-hash"))
        assertEquals("",UsageEvent.profileId("https://private.example/secret"))
        val row=UsageEvent(UsageAction.SEARCH,UsageSurface.PICKER,searchMode=UsageSearchMode.NONE,results=-1,latencyMillis=-2)
            .bucket(today.atTime(23,58))
        assertEquals(UsageSearchMode.NONE,row.searchMode); assertEquals(0L,row.results); assertEquals(0L,row.latencyMillis)
        assertEquals(today.toEpochDay(),row.day); assertEquals(23,row.hour)
    }
}
