package one.njk.sao.floating

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PickerBrowseState(val query: MediaQuery = MediaQuery(), val focusedId: String? = null)
data class PickerResultsSnapshot(val sourceId: String, val query: MediaQuery, val items: List<MediaItem>,
    val nextCursor: String?, val seenCursors: Set<String> = emptySet())

/** Only a small value snapshot crosses finished picker instances. No UI references, jobs or disk IO. */
@Singleton
class PickerBrowseSession @Inject constructor() {
    private val state = MutableStateFlow(PickerBrowseState())
    // Only the most recently browsed result set. No credentials, bitmaps, views or disk writes.
    private var results: PickerResultsSnapshot? = null
    fun snapshot(): PickerBrowseState = state.value
    fun remember(value: PickerBrowseState) { state.value = value }
    fun results(sourceId: String, query: MediaQuery) = results?.takeIf { it.sourceId == sourceId && it.query == query }
    fun rememberResults(value: PickerResultsSnapshot) { results = value }
    fun reset() { state.value = PickerBrowseState(); results = null }
}
