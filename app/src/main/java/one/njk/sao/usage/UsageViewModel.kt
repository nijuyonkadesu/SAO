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

data class UsageUiState(val report: UsageReport? = null,val enabled: Boolean? = null,val error: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageViewModel @Inject constructor(private val repository: UsageRepository,private val saved: SavedStateHandle,
    profiles: SourceProfiles,dispatchers: FloatingDispatchers): ViewModel() {
    private val page=runCatching { UsagePage.valueOf(saved["usage_page"] ?: "OVERVIEW") }.getOrDefault(UsagePage.OVERVIEW)
    private fun anchor(page: UsagePage)=runCatching { LocalDate.parse(saved.get<String>("usage_anchor_$page")) }.getOrDefault(LocalDate.now())
    private val selection=MutableStateFlow(UsageSelection(page,anchor(page),WeekFields.of(Locale.getDefault()).firstDayOfWeek))
    val selectionState=selection.asStateFlow()
    private val today=MutableStateFlow(LocalDate.now())
    val sourceNames=profiles.snapshots.map { snapshot -> snapshot.catalog.profiles.associate { it.id to it.name } }
        .catch { error -> if(error is CancellationException) throw error; emit(emptyMap()) }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),emptyMap())
    val state=combine(selection,today) { selected,now -> selected to now }.flatMapLatest { (selected,now) ->
        combine(repository.buckets(selected),repository.days,repository.enabled,repository.failure) { buckets,days,enabled,error ->
            UsageUiState(UsageReport(selected,buckets,days.filter { it.day<=now.toEpochDay() },now),enabled,error)
        }.flowOn(dispatchers.cpu).catch { error ->
            if(error is CancellationException) throw error
            emit(UsageUiState(error=true))
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),UsageUiState())
    fun refresh(locale: Locale) {
        today.value=LocalDate.now()
        selection.value=selection.value.copy(firstWeekday=WeekFields.of(locale).firstDayOfWeek)
    }
    fun page(value: UsagePage) { saved["usage_page"]=value.name; selection.value=selection.value.copy(page=value,anchor=anchor(value).coerceAtMost(today.value)) }
    fun date(value: LocalDate) {
        val actual=value.coerceAtMost(today.value)
        saved["usage_anchor_${selection.value.page}"]=actual.toString()
        selection.value=selection.value.copy(anchor=actual)
    }
    fun step(direction: Long) {
        val selected=selection.value
        date(when(selected.page) {
            UsagePage.OVERVIEW -> selected.start.plusMonths(direction)
            UsagePage.DAY -> selected.start.plusDays(direction)
            UsagePage.WEEK -> selected.start.plusWeeks(direction)
            UsagePage.YEAR -> selected.start.plusYears(direction)
        })
    }
    fun openDay(value: LocalDate) { page(UsagePage.DAY); date(value) }
    fun enabled(value: Boolean) { viewModelScope.launch { try { repository.enabled(value) } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { /* Repository exposes the error without losing existing graphs. */ } } }
    fun clear() { viewModelScope.launch { try { repository.clear() } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { /* Displayed through repository.failure. */ } } }
    fun scroll(page: UsagePage): Int = saved["usage_scroll_$page"] ?: 0
    fun scroll(page: UsagePage,y: Int) { saved["usage_scroll_$page"]=y }
    var heatmapMonth: Int
        get()=saved["usage_heatmap_month"] ?: (LocalDate.now().monthValue-1)
        set(value) { saved["usage_heatmap_month"]=value }
}
