package one.njk.sao.floating

import one.njk.sao.source.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

data class SearchOptionsState(val sourceId: String = LOCAL_SOURCE_ID, val loaded: Boolean = false, val supported: Boolean = false,
    val randomSupported: Boolean = false,
    val facets: Set<SearchFacet> = emptySet(),
    val defaultMode: SearchMode = SearchMode.CONTEXT, val modes: Set<SearchMode> = emptySet(),
    val peopleName: String = "", val people: List<SearchPerson> = emptyList(), val nextPeoplePage: Int? = null,
    val peopleLoading: Boolean = false, val peopleError: Boolean = false,
    val countries: List<String> = emptyList(), val states: List<String> = emptyList(), val cities: List<String> = emptyList(),
    val placesLoading: Boolean = false, val placesError: Boolean = false)

/** Demand-loaded filter choices. Source changes cancel old work; failures need an explicit retry. */
@HiltViewModel
class SearchOptionsViewModel @Inject constructor(private val source: MediaSource,
    private val dispatchers: FloatingDispatchers) : ViewModel() {
    private val mutable = MutableStateFlow(SearchOptionsState())
    val state = mutable.asStateFlow()
    private var peopleJob: Job? = null
    private var placesJob: Job? = null
    private var peopleKey: Pair<String, String>? = null
    private var failedPeoplePage: Int? = null
    private var placesKey: Triple<String, String?, String?>? = null
    private var countriesLoaded = false
    private var statesKey: String? = null
    private var statesLoaded = false
    private var citiesKey: Pair<String?, String?>? = null
    init {
        viewModelScope.launch {
            combine(source.identity,source.capabilities) { id, capabilities -> id to capabilities }.collect { (id, value) ->
                if (mutable.value.sourceId != id) {
                    peopleJob?.cancel(); placesJob?.cancel(); peopleKey = null; placesKey = null
                    failedPeoplePage = null
                    countriesLoaded = false; statesLoaded = false; statesKey = null; citiesKey = null
                    mutable.value = SearchOptionsState(sourceId = id)
                }
                // Identity and capability flows may arrive in either order. Never apply another
                // profile's modes, and never erase restored controls during an initial load.
                if (value.sourceId == id) mutable.value = mutable.value.copy(loaded=true,
                    supported=value.searchOptions,randomSupported=value.random,modes=value.modes,
                    facets=value.facets,defaultMode=value.defaultMode)
            }
        }
    }

    fun people(name: String = mutable.value.peopleName, more: Boolean = false, retry: Boolean = false) {
        if (SearchFacet.PEOPLE !in mutable.value.facets) return
        val id = mutable.value.sourceId
        val key = id to name.trim()
        if (more && (mutable.value.peopleLoading || mutable.value.nextPeoplePage == null)) return
        if (!more && !retry && key == peopleKey) return
        val page = if (more) mutable.value.nextPeoplePage!! else if (retry && key == peopleKey) failedPeoplePage ?: 1 else 1
        val previous = if (page > 1) mutable.value.people else emptyList()
        failedPeoplePage = null
        peopleKey = key; peopleJob?.cancel()
        mutable.value = mutable.value.copy(peopleName = name, people = previous, peopleLoading = true, peopleError = false)
        peopleJob = viewModelScope.launch {
            try {
                if (!more && !retry && name.isNotBlank()) delay(300)
                val result = withContext(dispatchers.io) { source.withSource(id) { it.people(name, page) } }
                if (mutable.value.sourceId == id) mutable.value = mutable.value.copy(
                    people = (previous + result.people).distinctBy { it.id }, nextPeoplePage = result.nextPage, peopleLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (mutable.value.sourceId == id) {
                failedPeoplePage = page
                mutable.value = mutable.value.copy(peopleLoading = false, peopleError = true)
            } }
        }
    }
    fun places(country: String?, state: String?, retry: Boolean = false) {
        if (SearchFacet.PLACES !in mutable.value.facets) return
        val id = mutable.value.sourceId
        val key = Triple(id, country, state)
        if (!retry && key == placesKey) return
        placesKey = key; placesJob?.cancel()
        mutable.value = mutable.value.copy(placesLoading = true, placesError = false)
        placesJob = viewModelScope.launch {
            try {
                val result = withContext(dispatchers.io) { source.withSource(id) { owned -> coroutineScope {
                    val countries = async { if (countriesLoaded) mutable.value.countries else owned.places(PlaceScope(PlaceLevel.COUNTRY)) }
                    val states = async { if (statesLoaded && statesKey == country) mutable.value.states else owned.places(PlaceScope(PlaceLevel.STATE, country = country)) }
                    val cities = async { if (citiesKey == (country to state)) mutable.value.cities else owned.places(PlaceScope(PlaceLevel.CITY, country = country, state = state)) }
                    Triple(countries.await(), states.await(), cities.await())
                } } }
                if (mutable.value.sourceId == id) {
                    countriesLoaded = true; statesLoaded = true; statesKey = country; citiesKey = country to state
                    mutable.value = mutable.value.copy(countries = result.first, states = result.second, cities = result.third, placesLoading = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (mutable.value.sourceId == id) mutable.value = mutable.value.copy(placesLoading = false, placesError = true) }
        }
    }
}
