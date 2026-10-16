package one.njk.sao.floating

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PickerState(val items: List<MediaItem> = emptyList(), val loading: Boolean = true,
    val error: Boolean = false, val query: MediaQuery = MediaQuery(), val loadingMore: Boolean = false,
    val hasMore: Boolean = false, val sourceId: String = "local", val sequenceId: String? = null)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PickerViewModel @Inject constructor(private val source: MediaSource,
    private val preferences: FloatingPreferences,
    private val exports: MediaExports, private val clipboard: ClipboardPublisher,
    private val session: PickerBrowseSession = PickerBrowseSession(),
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val dispatchers: FloatingDispatchers = FloatingDispatchers()) : ViewModel() {
    private val visibilityOwner = Any()
    private var pickerShowing = false
    internal fun pickerVisible(visible: Boolean) {
        pickerShowing = visible
        FloatingSession.pickerVisible(visibilityOwner, visible)
        session.sequenceVisible(visibilityOwner, visible)
    }
    private val initial = if (savedState.contains(QUERY_TEXT)) PickerBrowseState(
        MediaQuery(savedState[QUERY_TEXT] ?: "", savedState[QUERY_FILTER],
            SearchMode.entries.firstOrNull { it.name == savedState.get<String>(QUERY_MODE) } ?: SearchMode.CONTEXT,
            SearchQueryState.decode(savedState[QUERY_CONSTRAINTS]), savedState[QUERY_RANDOM]), savedState[FOCUSED_ID])
        else session.snapshot()
    private val queryState = MutableStateFlow(initial.query)
    val query = queryState.asStateFlow()
    private var searchFocusedId: String? = initial.focusedId
    val focusedId: String? get() = if (sequence.state.value == null) searchFocusedId else sequence.focusedId
    private var rememberedQuery: MediaQuery? = null
    private val sequence = session.acquireSequence(visibilityOwner, source, dispatchers, initial.query)
    val canBrowseSequence = source.sequenceBrowse.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init { rememberBrowsing() }

    internal val currentSequenceId get() = sequence.state.value?.id
    internal fun isCurrentBrowse(key: PickerBrowseKey) = session.ownsSequence(visibilityOwner) && key.sourceId == appliedSource &&
        key.query == query.value && key.sequenceId == currentSequenceId
    internal fun focusForBrowse(sequenceId: String?): String? =
        if (sequenceId == null) searchFocusedId else sequence.focusedId

    fun focus(id: String?, sequenceId: String? = currentSequenceId) {
        if (!session.ownsSequence(visibilityOwner) || sequenceId != currentSequenceId) return
        if (focusedId == id) return
        if (sequenceId == null) searchFocusedId = id else sequence.focus(id)
        rememberBrowsing()
    }
    fun rememberBrowsing() {
        if (!session.ownsSequence(visibilityOwner)) return
        // SavedStateHandle is serialized by Android's lifecycle saved-state mechanism, not DataStore.
        if (rememberedQuery != query.value) {
            rememberedQuery = query.value
            savedState[QUERY_TEXT] = query.value.text
            savedState[QUERY_FILTER] = query.value.filter
            savedState[QUERY_MODE] = query.value.mode.name
            savedState[QUERY_CONSTRAINTS] = SearchQueryState.encode(query.value.constraints)
            savedState[QUERY_RANDOM] = query.value.randomRequest
        }
        val searchFocus = searchFocusedId
        savedState[FOCUSED_ID] = searchFocus
        session.remember(PickerBrowseState(query.value, searchFocus))
    }
    val selection = MutableStateFlow<MediaItem?>(null)
    val copyStatus = MutableStateFlow<Int>(one.njk.sao.R.string.picker_choose)
    private val request = ClipboardRequest()
    private var copying: Job? = null
    private var preferredOverride: Boolean? = null
    fun clearSelection() { request.next(); copying?.cancel(); selection.value = null; copyStatus.value = one.njk.sao.R.string.picker_choose }
    /** An explicit dock action adopts the center; scrolling alone never changes clipboard work. */
    fun selectForAction(item: MediaItem) {
        if (selection.value == item) return
        clearSelection(); selection.value = item
    }
    fun select(item: MediaItem, target: MessagingTarget) { selectForAction(item); copy(target) }
    fun copy(target: MessagingTarget, prefer: Boolean? = preferredOverride) {
        val item = selection.value ?: return
        val version = request.next(); copying?.cancel()
        copyStatus.value = one.njk.sao.R.string.picker_copying
        copying = viewModelScope.launch {
            try {
                val choice = DeliveryPolicy.clipboard(item, prefer ?: preferences.current().preferStickers, target)
                val media = exports.prepare(item, choice.rendition)
                if (source.identity.first() == (item.remote?.sourceId ?: "local") && clipboard.publish(item, media, request, version)) copyStatus.value = when {
                    item.isVideo -> one.njk.sao.R.string.picker_video_copied
                    item.animated -> one.njk.sao.R.string.picker_gif_copied
                    choice.mediaFallback -> one.njk.sao.R.string.picker_media_fallback
                    choice.rendition == Rendition.STICKER_PNG -> one.njk.sao.R.string.picker_sticker_copied
                    choice.rendition == Rendition.STICKER_WEBP -> one.njk.sao.R.string.picker_telegram_sticker_copied
                    else -> one.njk.sao.R.string.picker_copied
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request.current(version)) copyStatus.value = one.njk.sao.R.string.picker_copy_failed }
        }
    }
    suspend fun prepareClipboard(target: MessagingTarget): Boolean {
        val item = selection.value ?: return false
        val version = request.next(); copying?.cancel()
        val choice = DeliveryPolicy.clipboard(item, preferredOverride ?: preferences.current().preferStickers, target)
        val media = exports.prepare(item, choice.rendition)
        return source.identity.first() == (item.remote?.sourceId ?: "local") && clipboard.publish(item, media, request, version)
    }
    override fun onCleared() { session.releaseSequence(visibilityOwner); request.next(); pickerVisible(false) }

    val options = preferences.options.onEach {
        if (preferredOverride == it.preferStickers) preferredOverride = null
        if (!it.browseSequence) backToResults()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FloatingOptions())
    private val pageRequests = Channel<Unit>(Channel.CONFLATED)
    private var requestingPage = false
    private val refresh = MutableStateFlow(0)
    private var appliedSource: String? = null
    private val resultsState = combine(query.debounce(180), source.identity, refresh) { value, id, _ -> value to id }
        .flatMapLatest { (query, sourceId) -> flow {
            while (pageRequests.tryReceive().isSuccess) { /* Drop requests from the old result set. */ }
            requestingPage = false
            if (session.ownsSequence(visibilityOwner) && (appliedSource != null && appliedSource != sourceId ||
                sequence.state.value?.sourceId?.let { it != sourceId } == true)) {
                sequence.close(); searchFocusedId = null; clearSelection(); rememberBrowsing()
            }
            appliedSource = sourceId
            var snapshot = session.results(sourceId, query)
            if (snapshot == null) {
                emit(PickerState(query = query, sourceId = sourceId))
                val page = source.page(query)
                if (source.identity.first() != sourceId) throw CancellationException("Source changed")
                snapshot = PickerResultsSnapshot(sourceId, query, uniqueItems(page.items), page.nextCursor)
                session.rememberResults(snapshot)
            }
            var current = requireNotNull(snapshot)
            var pageError = false
            while (true) {
                emit(PickerState(current.items, loading = false, error = pageError, query = query, hasMore = current.nextCursor != null, sourceId = sourceId))
                requestingPage = false
                pageRequests.receive()
                val cursor = current.nextCursor
                emit(PickerState(current.items, loading = false, query = query, loadingMore = true, sourceId = sourceId))
                try {
                    val page = source.page(query, cursor)
                    if (source.identity.first() != sourceId) throw CancellationException("Source changed")
                    val seen = current.seenCursors + listOfNotNull(cursor)
                    current = PickerResultsSnapshot(sourceId, query,
                        uniqueItems(if (cursor == null) page.items else current.items + page.items),
                        page.nextCursor?.takeUnless { it in seen }, seen)
                    session.rememberResults(current)
                    pageError = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { pageError = true }
            }
        }.catch { emit(PickerState(loading = false, error = true, query = query, sourceId = sourceId)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PickerState())
    val state = combine(resultsState, sequence.state) { results, nearby ->
        if (nearby == null || nearby.sourceId != results.sourceId) results else PickerState(
            nearby.items, nearby.loading, nearby.error, results.query, nearby.loadingMore,
            nearby.newer != null || nearby.older != null, nearby.sourceId, nearby.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PickerState())
    fun browseSequence(item: MediaItem) {
        if (currentSequenceId != null) return
        val current = state.value
        if (!pickerShowing || !session.ownsSequence(visibilityOwner) || !options.value.browseSequence || !canBrowseSequence.value || current.loading || current.loadingMore ||
            current.query != query.value || current.items.none { it.id == item.id }) return
        sequence.open(item, current.sourceId)
    }
    fun backToResults(): Boolean {
        if (!session.ownsSequence(visibilityOwner) || sequence.state.value == null) return false
        sequence.close(); rememberBrowsing()
        return true
    }
    fun loadMore(position: Int) {
        if (!pickerShowing || !session.ownsSequence(visibilityOwner)) return
        if (sequence.state.value != null) { sequence.loadMore(position); return }
        val value = state.value
        if (value.query == query.value && value.hasMore && !value.error && !value.loading && !value.loadingMore && !requestingPage && position >= value.items.size - 10) {
            requestingPage = true; pageRequests.trySend(Unit)
        }
    }
    fun retry() {
        if (!pickerShowing || !session.ownsSequence(visibilityOwner)) return
        if (sequence.state.value != null) sequence.retry()
        else if (state.value.items.isNotEmpty()) pageRequests.trySend(Unit) else refresh.value++
    }
    private suspend fun uniqueItems(items: List<MediaItem>) = withContext(dispatchers.cpu) {
        // Stable remote asset identity avoids duplicates if its revision changes between pages.
        items.distinctBy { it.remote?.assetId ?: it.id }
    }
    private fun searchQuery(value: MediaQuery) {
        if (!session.ownsSequence(visibilityOwner) || query.value == value) return
        backToResults()
        queryState.value = value; searchFocusedId = null
        rememberBrowsing()
    }
    fun search(text: String) {
        // EditText's saved-view restoration can emit the same empty text as a random batch.
        if (text != query.value.text) searchQuery(query.value.copy(text = text, randomRequest = null))
    }
    fun filter(value: String?) { searchQuery(query.value.copy(filter = value)) }
    fun searchMode(value: SearchMode) { searchQuery(query.value.copy(mode = value, randomRequest = null)) }
    fun searchConstraints(value: SearchConstraints) { searchQuery(query.value.copy(constraints = value)) }
    fun clearSearchConstraints() { searchQuery(query.value.copy(constraints = SearchConstraints())) }
    fun lucky() {
        // Disable overlapping taps while the debounce or source read is in flight.
        if (state.value.loading || state.value.loadingMore || state.value.query != query.value) return
        clearSelection()
        searchQuery(query.value.copy(text = "", randomRequest = java.util.UUID.randomUUID().toString()))
    }
    fun preferStickers(value: Boolean) {
        preferredOverride = value
        viewModelScope.launch { preferences.preferStickers(value) }
    }
    companion object {
        internal const val QUERY_TEXT = "picker_query_text"
        internal const val QUERY_FILTER = "picker_query_filter"
        internal const val QUERY_MODE = "picker_query_mode"
        internal const val QUERY_CONSTRAINTS = "picker_query_constraints"
        internal const val QUERY_RANDOM = "picker_query_random"
        internal const val FOCUSED_ID = "picker_focused_id"
    }

}
