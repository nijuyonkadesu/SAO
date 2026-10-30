package one.njk.sao.usage

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class UsageHistoryTest {
    private val monday=DayOfWeek.MONDAY
    private fun day(date: String,count: Long)=UsageDay(LocalDate.parse(date).toEpochDay(),count,count,0,0)
    @Test fun unchangedHistoryAndOtherPeriodsKeepTheirPreparedObjects() {
        val index=UsageHistoryIndex()
        val today=LocalDate.of(2026,1,3)
        val rows=listOf(day("2024-01-01",2),day("2024-06-01",4),day("2025-12-31",3),day("2026-01-02",5))
        val first=index.update(rows,today,monday)
        assertSame(first,index.update(rows.toList(),today,monday))
        val changed=index.update(rows.dropLast(1)+day("2026-01-02",7),today,monday)
        assertSame(first.years[2024],changed.years[2024]); assertSame(first.years[2025],changed.years[2025])
        assertSame(first.months[YearMonth.of(2025,12)],changed.months[YearMonth.of(2025,12)])
        assertNotSame(first.years[2026],changed.years[2026])
        assertEquals(16L,changed.lifetime.actions)
        val late=index.update(rows.dropLast(1).drop(1)+day("2024-01-01",9)+day("2026-01-02",7),today,monday)
        assertNotSame(changed.years[2024],late.years[2024])
        assertSame(changed.months[YearMonth.of(2024,6)],late.months[YearMonth.of(2024,6)])
        assertSame(changed.years[2026],late.years[2026])
        assertEquals(23L,late.lifetime.actions)
    }
    @Test fun selectingAnotherDayReusesHistoryAndTheLoadedWeek() {
        val today=LocalDate.of(2026,1,8)
        val rows=listOf(day("2026-01-05",2),day("2026-01-06",3))
        val past=UsageHistoryIndex().update(rows,today,monday)
        val selected=UsageSelection(UsageRange.WEEK,LocalDate.of(2026,1,5),monday)
        val buckets=rows.map { UsageBucket(it.day,9,UsageAction.SEARCH,UsageSurface.PICKER,occurrences=it.actions) }
        val details=UsageWeekDetails(selected,buckets,today)
        val first=UsageDashboard(selected,buckets,past.days,today,past,details)
        val second=UsageDashboard(selected.copy(anchor=selected.anchor.plusDays(1)),buckets,past.days,today,past,details)
        assertSame(first.calendarMonths,second.calendarMonths)
        assertSame(first.year.daily,second.year.daily)
        assertSame(first.week.hourly,second.week.hourly)
        assertSame(first.lifetime,second.lifetime)
        assertEquals(2L,first.day.actions); assertEquals(3L,second.day.actions)
        assertEquals(3L,second.day.hourly[9])
    }
    @Test fun clockChangesClearAndDeletionInvalidateOnlyWhatChanged() {
        val index=UsageHistoryIndex()
        val rows=listOf(day("2024-12-31",2),day("2025-01-01",3))
        val first=index.update(rows,LocalDate.of(2024,12,31),monday)
        assertFalse(first.years.containsKey(2025)); assertEquals(2L,first.lifetime.actions)
        val next=index.update(rows,LocalDate.of(2025,1,1),monday)
        assertSame(first.years[2024],next.years[2024]); assertEquals(2,next.streak.current)
        val later=index.update(rows,LocalDate.of(2025,1,3),monday)
        assertSame(next.months,later.months); assertEquals(0,later.streak.current); assertEquals(2,later.streak.best)
        val removed=index.update(rows.drop(1),later.today,monday)
        assertFalse(removed.years.containsKey(2024)); assertEquals(3L,removed.lifetime.actions)
        val cleared=index.update(emptyList(),later.today,monday)
        assertTrue(cleared.months.isEmpty()); assertTrue(cleared.weeks.isEmpty()); assertTrue(cleared.daySummaries.isEmpty())
        assertEquals(UsageTotals(),cleared.lifetime); assertEquals(UsageStreak(),cleared.streak)
    }
    @Test fun localeChangesRebuildWeekDependentRecords() {
        val index=UsageHistoryIndex()
        val rows=listOf(day("2026-01-04",3),day("2026-01-05",7))
        val today=LocalDate.of(2026,1,6)
        val first=index.update(rows,today,monday)
        val changed=index.update(rows,today,DayOfWeek.SUNDAY)
        assertEquals(7L,first.years.getValue(2026).bestWeek)
        assertEquals(10L,changed.years.getValue(2026).bestWeek)
        assertEquals(first.lifetime,changed.lifetime)
        assertNotSame(first.years[2026],changed.years[2026])
    }
    @Test fun roomEmissionsMustAgreeBeforePublishingADashboard() {
        val today=LocalDate.of(2026,1,6)
        val selected=UsageSelection(UsageRange.WEEK,today,monday)
        val bucket=UsageBucket(today.toEpochDay(),10,UsageAction.COPY_MEDIA,UsageSurface.PICKER,occurrences=2)
        val past=UsageHistoryIndex().update(listOf(bucket.dayDelta()),today,monday)
        assertTrue(UsageWeekDetails(selected,listOf(bucket),today).matches(past))
        assertFalse(UsageWeekDetails(selected,listOf(bucket.copy(occurrences=3)),today).matches(past))
        assertFalse(UsageWeekDetails(selected,listOf(bucket.copy(outcome=UsageOutcome.FAILED)),today).matches(past))
    }
}
