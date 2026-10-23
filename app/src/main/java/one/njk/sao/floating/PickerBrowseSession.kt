package one.njk.sao.floating

import one.njk.sao.source.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject
import javax.inject.Singleton

data class PickerBrowseState(val query: MediaQuery = MediaQuery(), val focusedId: MediaIdentity? = null)
data class PickerResultsSnapshot(val sourceId: String, val query: MediaQuery, val items: List<MediaItem>,
    val nextCursor: String?, val seenCursors: Set<String> = emptySet(), val interrupted: Boolean = false)

/** Process-local browsing state, with no Activity, ViewModel, view, bitmap or disk storage. */
@Singleton
class PickerBrowseSession @Inject constructor() {
    private val state = MutableStateFlow(PickerBrowseState())
    // Only the most recently browsed result set. No credentials, bitmaps, views or disk writes.
    private var results: PickerResultsSnapshot? = null
    private var sequence: PickerSequenceNavigator? = null
    private var sequenceSource: MediaSource? = null
    private var sequenceScope: CoroutineScope? = null
    private var sequenceOwner: Any? = null
    fun snapshot(): PickerBrowseState = state.value
    fun remember(value: PickerBrowseState) {
        if (state.value.query != value.query) sequence?.close()
        state.value = value
    }
    fun results(sourceId: String, query: MediaQuery) = results?.takeIf { it.sourceId == sourceId && it.query == query }
    fun rememberResults(value: PickerResultsSnapshot) { results = value }
    internal fun acquireSequence(owner: Any, source: MediaSource, dispatchers: FloatingDispatchers,
        query: MediaQuery): PickerSequenceNavigator {
        sequence?.pause()
        if (sequenceSource !== source) {
            sequence?.close(); sequenceScope?.cancel()
            val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
            sequenceScope = scope; sequenceSource = source
            sequence = PickerSequenceNavigator(source, scope, dispatchers)
        }
        if (state.value.query != query) sequence?.close()
        sequenceOwner = owner
        return requireNotNull(sequence)
    }
    internal fun ownsSequence(owner: Any) = sequenceOwner === owner
    internal fun sequenceVisible(owner: Any, visible: Boolean) {
        if (!ownsSequence(owner)) return
        if (visible) sequence?.resume() else sequence?.pause()
    }
    internal fun releaseSequence(owner: Any) {
        if (!ownsSequence(owner)) return
        sequence?.pause(); sequenceOwner = null
    }
    fun reset() {
        sequence?.close(); sequenceScope?.cancel()
        sequence = null; sequenceSource = null; sequenceScope = null; sequenceOwner = null
        state.value = PickerBrowseState(); results = null
    }
}
