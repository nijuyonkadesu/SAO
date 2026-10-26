package one.njk.sao.usage

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class UsageReportTest {
    private val today=LocalDate.of(2026,10,26)
    private fun day(date: String,actions: Long=1)=UsageDay(LocalDate.parse(date).toEpochDay(),actions,0,0,0)
    @Test fun emptyHistoryHasNoArtificialRecordsOrStreak() {
        val report=UsageReport(UsageSelection(UsageRange.WEEK,today,DayOfWeek.MONDAY),emptyList(),emptyList(),today)
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
        val monday=UsageSelection(UsageRange.WEEK,date,DayOfWeek.MONDAY)
        val sunday=monday.copy(firstWeekday=DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026,12,28),monday.start)
        assertEquals(LocalDate.of(2027,1,4),monday.endExclusive)
        assertEquals(LocalDate.of(2026,12,27),sunday.start)
        val report=UsageReport(monday,emptyList(),listOf(day("2026-12-28",3),day("2027-01-01",7),day("2027-01-04",99)),date.plusDays(4))
        assertEquals(10L,report.actions); assertEquals(listOf(3L,0,0,0,7,0,0),report.daily)
    }
    @Test fun leapYearAndAnnualRecordsAreLimitedToSelectedYear() {
        val selection=UsageSelection(UsageRange.YEAR,LocalDate.of(2024,2,29),DayOfWeek.MONDAY)
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
        val report=UsageReport(UsageSelection(UsageRange.DAY,today,DayOfWeek.MONDAY),rows,emptyList(),today)
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
    @Test fun heatmapIntensityDistinguishesHeavyDaysWithoutLettingOutliersFlattenTheYear() {
        val intensity=UsageIntensity(listOf(0,1,2,3,4,5,6,7,10000))
        assertEquals((0..4).toList(),listOf(0L,1,3,5,10000).map(intensity::level))
        assertEquals(listOf(2,4),listOf(3L,8).map(UsageIntensity(listOf(3,8))::level))
        assertEquals(4,UsageIntensity(listOf(9,9,9)).level(9))
        assertEquals(0,UsageIntensity(emptyList()).level(0))
    }
    @Test fun dashboardSharesOneDateWhileKeepingDayWeekAndAnnualCountsSeparate() {
        val date=LocalDate.of(2027,1,1)
        val days=listOf(day("2026-12-28",3),day("2027-01-01",7),day("2027-01-04",99))
        val rows=days.map { UsageBucket(it.day,12,UsageAction.COPY_MEDIA,UsageSurface.PICKER,occurrences=it.actions) }
        val report=UsageDashboard(UsageSelection(UsageRange.WEEK,date,DayOfWeek.MONDAY),rows.take(2),days,date.plusDays(4))
        assertEquals(7L,report.day.actions); assertEquals(7L,report.day.hourly[12])
        assertEquals(10L,report.week.actions); assertEquals(10L,report.week.actionCounts.single().count)
        assertEquals(106L,report.year.actions); assertTrue(report.year.buckets.isEmpty())
    }
    @Test fun monthlyTotalsRespectLeapDaysMonthBoundariesAndFutureDates() {
        val selected=LocalDate.of(2024,2,29)
        val dashboard=UsageDashboard(UsageSelection(UsageRange.WEEK,selected,DayOfWeek.MONDAY),emptyList(),
            listOf(day("2024-01-31",99),day("2024-02-01",3),day("2024-02-29",7),day("2024-03-01",99)),selected)
        assertEquals(LocalDate.of(2024,2,1),dashboard.month.selection.start)
        assertEquals(LocalDate.of(2024,3,1),dashboard.month.selection.endExclusive)
        assertEquals(29,dashboard.month.daily.size); assertEquals(10L,dashboard.month.actions)
        assertEquals(2,dashboard.month.activeDays); assertTrue(dashboard.month.buckets.isEmpty())
        val beforeLeapDay=dashboard.copy(today=LocalDate.of(2024,2,28))
        assertEquals(3L,beforeLeapDay.month.actions)
        assertEquals(31,UsageReport(UsageSelection(UsageRange.MONTH,today,DayOfWeek.SUNDAY),emptyList(),emptyList(),today).daily.size)
    }
}
