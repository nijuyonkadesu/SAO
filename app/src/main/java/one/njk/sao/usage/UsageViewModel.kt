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
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class UsageUiState(val report: UsageDashboard? = null,val enabled: Boolean? = null,val error: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageViewModel @Inject constructor(private val repository: UsageRepository,private val saved: SavedStateHandle,
    profiles: SourceProfiles,dispatchers: FloatingDispatchers): ViewModel() {
    private val anchor=runCatching { LocalDate.parse(saved.get<String>("usage_anchor")) }.getOrDefault(LocalDate.now())
    private val selection=MutableStateFlow(UsageSelection(UsageRange.WEEK,anchor,WeekFields.of(Locale.getDefault()).firstDayOfWeek))
    val selectionState=selection.asStateFlow()
    private val calendar=MutableStateFlow(runCatching { UsageCalendarMode.valueOf(saved["usage_calendar_mode"] ?: "YEAR") }.getOrDefault(UsageCalendarMode.YEAR))
    val calendarMode=calendar.asStateFlow()
    private val today=MutableStateFlow(LocalDate.now())
    val sourceNames=profiles.snapshots.map { snapshot -> snapshot.catalog.profiles.associate { it.id to it.name } }
        .catch { error -> if(error is CancellationException) throw error; emit(emptyMap()) }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),emptyMap())
    val state=combine(selection,today) { selected,now -> selected to now }.flatMapLatest { (selected,now) ->
        combine(repository.buckets(selected),repository.days,repository.enabled,repository.failure) { buckets,days,enabled,error ->
            UsageUiState(UsageDashboard(selected,buckets,days.filter { it.day<=now.toEpochDay() },now),enabled,error)
        }.flowOn(dispatchers.cpu).catch { error ->
            if(error is CancellationException) throw error
            emit(UsageUiState(error=true))
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),UsageUiState())
    fun refresh(locale: Locale) {
        today.value=LocalDate.now()
        selection.value=selection.value.copy(firstWeekday=WeekFields.of(locale).firstDayOfWeek)
    }
    fun date(value: LocalDate) {
        val actual=value.coerceAtMost(today.value)
        saved["usage_anchor"]=actual.toString()
        selection.value=selection.value.copy(anchor=actual)
    }
    fun step(direction: Long)=date(selection.value.anchor.plusDays(direction))
    fun calendarMode(value: UsageCalendarMode) { saved["usage_calendar_mode"]=value.name; calendar.value=value }
    fun stepCalendar(direction: Long)=date(when(calendar.value) {
        UsageCalendarMode.MONTH -> selection.value.anchor.plusMonths(direction)
        UsageCalendarMode.YEAR -> selection.value.anchor.plusYears(direction)
    })
    fun enabled(value: Boolean) { viewModelScope.launch { try { repository.enabled(value) } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { /* Repository exposes the error without losing existing graphs. */ } } }
    fun clear() { viewModelScope.launch { try { repository.clear() } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { /* Displayed through repository.failure. */ } } }
    var scroll: Int
        get()=saved["usage_scroll"] ?: 0
        set(value) { saved["usage_scroll"]=value }
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
