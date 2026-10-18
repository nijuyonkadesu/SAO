package one.njk.sao.viewmodels

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import one.njk.sao.BuildConfig
import one.njk.sao.R
import one.njk.sao.database.Bookmark
import one.njk.sao.database.BookmarksDao
import one.njk.sao.database.SettingsDataStore
import one.njk.sao.floating.PickerBrowseKey
import one.njk.sao.floating.PickerState
import one.njk.sao.floating.SourceReadException
import one.njk.sao.source.*
import javax.inject.Inject

data class HomeState(val browse: PickerState = PickerState(), val categories: List<SourceCategory> = emptyList(),
    val categoryId: String? = null)

/** One live result set, retained across view recreation; no query-result or scroll preference writes. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtsViewModel @Inject constructor(private val source: MediaSource,
    private val settingsDataStore: SettingsDataStore, private val bookmarksDao: BookmarksDao,
    private val saved: SavedStateHandle, private val dispatchers: FloatingDispatchers) : ViewModel() {
    private val category = MutableStateFlow(saved.get<String>("home_category"))
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private var paging = false
    private var retained: HomeState? = null
    private var cursor: String? = null
    private val seen = mutableSetOf<String>()
    var focusedId: String? = saved["home_focus"]
        private set
    private var applied: PickerBrowseKey? = null
    private data class Browse(val sourceId: String, val capabilities: SourceCapabilities, val category: SourceCategory?)
    val state = combine(source.identity, source.capabilities, category, settingsDataStore.currentModeFlow()) { id, declared, chosen, adult ->
        val caps=declared.copy(categories=declared.categories.filter { adult || !it.adult })
        if(caps.sourceId != id) null else Browse(id, caps,
            caps.categories.firstOrNull { it.id == chosen } ?: caps.categories.firstOrNull())
    }.filterNotNull().distinctUntilChanged().flatMapLatest { spec -> flow {
        val query = spec.capabilities.normalize(spec.category?.query ?: MediaQuery())
        val key = PickerBrowseKey(spec.sourceId, query, null)
        while(requests.tryReceive().isSuccess) { /* Discard old-source requests. */ }
        paging = false
        if(applied != key) {
            if(applied != null) focus(null)
            applied = key; retained = null; cursor = null; seen.clear()
        }
        var current = retained ?: HomeState(PickerState(query=query,sourceId=spec.sourceId),
            spec.capabilities.categories,spec.category?.id)
        if(current.browse.loading) {
            emit(current)
            try {
                val page = source.page(query)
                if(source.identity.first() != spec.sourceId) throw CancellationException("Source changed")
                cursor = page.nextCursor
                current = current.copy(browse=current.browse.copy(items=page.items.distinctBy { it.identityKey },
                    loading=false,hasMore=cursor!=null,error=page.interrupted,
                    errorMessage=if(page.interrupted) R.string.source_all_interrupted else null))
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { current = current.copy(browse=current.browse.copy(loading=false,error=true,
                errorMessage=(error as? SourceReadException)?.messageRes)) }
        }
        while(true) {
            retained = current; paging = false; emit(current)
            val changed = requests.receive()
            emit(current.copy(browse=current.browse.copy(loadingMore=true)))
            try {
                val page = source.page(query,if(changed) null else cursor)
                if(source.identity.first() != spec.sourceId) throw CancellationException("Source changed")
                if(changed) seen.clear() else cursor?.let { seen += it }
                val items = withContext(dispatchers.cpu) {
                    val old = if(changed) {
                        val latest=page.items.associateBy { it.identityKey }
                        current.browse.items.map { latest[it.identityKey] ?: it }
                    } else current.browse.items
                    (old + page.items).distinctBy { it.identityKey }
                }
                cursor = page.nextCursor?.takeUnless { it in seen }
                current = current.copy(browse=current.browse.copy(items=items,loadingMore=false,error=page.interrupted,
                    hasMore=cursor!=null,errorMessage=if(page.interrupted) R.string.source_all_interrupted else null))
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { current = current.copy(browse=current.browse.copy(loadingMore=false,error=true,
                errorMessage=(error as? SourceReadException)?.messageRes)) }
        }
    } }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),HomeState())

    internal fun current(key: PickerBrowseKey) = applied == key && state.value.browse.let {
        it.sourceId == key.sourceId && it.query == key.query && !it.loading
    }
    fun chooseCategory(id: String) {
        if(category.value == id) return
        category.value = id; saved["home_category"] = id
    }
    fun focus(id: String?) { if(focusedId == id) return; focusedId = id; saved["home_focus"] = id }
    fun loadMore(position: Int) {
        val value = state.value.browse
        if(!paging && !value.loading && !value.loadingMore && !value.error && value.hasMore && position >= value.items.size-10) {
            paging = true; requests.trySend(false)
        }
    }
    fun refreshCurrent() {
        val value=state.value.browse
        if(!paging && !value.loading && !value.loadingMore && !value.error && value.query.randomRequest==null) {
            paging=true; requests.trySend(true)
        }
    }
    fun retry() { if(!paging) { paging = true; requests.trySend(false) } }

    // Existing settings and bookmark storage remain available.
    val nsfwMode = settingsDataStore.currentModeFlow().asLiveData()
    fun toggleNsfwMode() { viewModelScope.launch { settingsDataStore.toggleMode() } }
    fun bookmark(item: MediaItem) { viewModelScope.launch(dispatchers.io) {
        bookmarksDao.save(Bookmark("sao-source:" + SourceWire.item(item).toString()))
    } }
    fun exportFav() { viewModelScope.launch(dispatchers.io) {
        if(BuildConfig.DEBUG) Log.d("fav", "${bookmarksDao.get()}")
    } }
}
