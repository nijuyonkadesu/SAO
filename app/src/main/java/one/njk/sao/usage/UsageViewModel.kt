package one.njk.sao.usage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import one.njk.sao.floating.SourceProfiles
import one.njk.sao.source.FloatingDispatchers
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class UsageUiState(val report: UsageDashboard? = null,val enabled: Boolean? = null,val error: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageViewModel @Inject constructor(private val repository: UsageRepository,private val saved: SavedStateHandle,
    profiles: SourceProfiles,dispatchers: FloatingDispatchers,private val session: UsageSession=UsageSession()): ViewModel() {
    private val lease=session.acquire()
    init { lease.snapshot.saved.forEach { (key,value) -> if(!saved.contains(key)) saved[key]=value } }
    private val anchor=runCatching { LocalDate.parse(saved.get<String>("usage_anchor")) }.getOrDefault(LocalDate.now())
    private val selection=MutableStateFlow(UsageSelection(UsageRange.WEEK,anchor,WeekFields.of(Locale.getDefault()).firstDayOfWeek))
    val selectionState=selection.asStateFlow()
    private val calendar=MutableStateFlow(runCatching { UsageCalendarMode.valueOf(saved["usage_calendar_mode"] ?: "YEAR") }.getOrDefault(UsageCalendarMode.YEAR))
    val calendarMode=calendar.asStateFlow()
    private val month=MutableStateFlow(runCatching { YearMonth.parse(saved.get<String>("usage_calendar_month")) }.getOrDefault(YearMonth.from(anchor)))
    val calendarMonth=month.asStateFlow()
    private val today=MutableStateFlow(LocalDate.now())
    val sourceNames=profiles.snapshots.map { snapshot -> snapshot.catalog.profiles.associate { it.id to it.name } }
        .catch { error -> if(error is CancellationException) throw error; emit(emptyMap()) }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),lease.snapshot.sourceNames)
    private val historyIndex by lazy { UsageHistoryIndex(lease.snapshot.state.report?.history) }
    private val weekday=selection.map { it.firstWeekday }.distinctUntilChanged()
    private val history=combine(repository.days,today,weekday) { days,now,first -> historyIndex.update(days,now,first) }
        .flowOn(dispatchers.cpu)
    // Changing a day inside the same week keeps its existing query and prepared breakdowns.
    private var preparedWeek: UsageWeekDetails?=lease.snapshot.state.report?.details
    private val week=selection.map { it.copy(range=UsageRange.WEEK,anchor=it.start) }.distinctUntilChanged()
        .flatMapLatest { period -> repository.buckets(period).map { period to it } }
        .combine(today) { (period,rows),now ->
            preparedWeek?.takeIf { it.period==period && it.today==now && it.buckets==rows }
                ?: UsageWeekDetails(period,rows.filter { it.day<=now.toEpochDay() },now).also { preparedWeek=it }
        }
        .flowOn(dispatchers.cpu)
    private val reports=combine(selection,history,week) { selected,past,details ->
        val period=selected.copy(range=UsageRange.WEEK)
        if(details.period.start!=period.start || details.period.endExclusive!=period.endExclusive ||
            details.period.firstWeekday!=selected.firstWeekday || past.firstWeekday!=selected.firstWeekday || details.today!=past.today || !details.matches(past)) null
        else UsageDashboard(selected,details.buckets,past.days,past.today,past,details)
    }.filterNotNull().flowOn(dispatchers.cpu)
    val state=combine(reports,repository.enabled,repository.failure) { report,enabled,error -> UsageUiState(report,enabled,error) }
        .catch { error -> if(error is CancellationException) throw error; emit(UsageUiState(error=true)) }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),lease.snapshot.state.takeIf {
            it.report?.selection==selection.value && it.report.today==today.value
        } ?: UsageUiState())
    fun remember() {
        session.remember(lease,UsageSession.Snapshot(saved.keys().associateWith { saved.get<Any?>(it) },state.value,sourceNames.value))
    }
    override fun onCleared() { remember() }
    fun refresh(locale: Locale) {
        today.value=LocalDate.now()
        selection.value=selection.value.copy(firstWeekday=WeekFields.of(locale).firstDayOfWeek)
    }
    fun date(value: LocalDate) {
        val actual=value.coerceAtMost(today.value)
        saved["usage_anchor"]=actual.toString()
        selection.value=selection.value.copy(anchor=actual)
        browseMonth(YearMonth.from(actual))
    }
    fun step(direction: Long)=date(selection.value.anchor.plusDays(direction))
    fun calendarMode(value: UsageCalendarMode) { saved["usage_calendar_mode"]=value.name; calendar.value=value }
    fun stepYear(direction: Long)=date(selection.value.anchor.plusYears(direction))
    fun browseMonth(value: YearMonth) {
        val actual=value.coerceAtMost(YearMonth.from(today.value))
        saved["usage_calendar_month"]=actual.toString(); month.value=actual
    }
    fun enabled(value: Boolean) { viewModelScope.launch { try { repository.enabled(value) } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { /* Repository exposes the error without losing existing graphs. */ } } }
    fun clear() { viewModelScope.launch { try { repository.clear() } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { /* Displayed through repository.failure. */ } } }
    var listState: android.os.Parcelable?
        get()=saved["usage_list_state"]
        set(value) { saved["usage_list_state"]=value }
    var heatmapYear: Int
        get()=saved["usage_heatmap_year"] ?: 0
        set(value) { saved["usage_heatmap_year"]=value }
    var heatmapDate: Long
        get()=saved["usage_heatmap_date"] ?: Long.MIN_VALUE
        set(value) { saved["usage_heatmap_date"]=value }
    var heatmapScroll: Int
        get()=saved["usage_heatmap_scroll"] ?: 0
        set(value) { saved["usage_heatmap_scroll"]=value }
}
