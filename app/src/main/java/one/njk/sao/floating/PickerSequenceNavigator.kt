package one.njk.sao.floating

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class SequenceState(val id: String, val sourceId: String, val anchor: MediaItem,
    val items: List<MediaItem> = listOf(anchor), val loading: Boolean = true,
    val newer: String? = null, val older: String? = null,
    val loadingMore: Boolean = false, val error: Boolean = false,
    val failedDirection: SequenceDirection? = null)

/** One level of temporary navigation. Owns cancellable reads, never the search result cache. */
internal class PickerSequenceNavigator(private val source: MediaSource, private val scope: CoroutineScope,
    private val dispatchers: FloatingDispatchers) : AutoCloseable {
    private val value = MutableStateFlow<SequenceState?>(null)
    val state = value.asStateFlow()
    private var work: Job? = null
    private var session: MediaSequence? = null
    private val seen = mutableSetOf<Pair<SequenceDirection, String>>()
    var focusedId: String? = null
        private set
    fun focus(id: String?) { focusedId = id }
    fun open(anchor: MediaItem, sourceId: String) {
        close()
        val start = SequenceState(UUID.randomUUID().toString(), sourceId, anchor)
        focus(anchor.id); value.value = start
        work = scope.launch {
            var opened: MediaSequence? = null
            try {
                opened = source.openSequence(anchor)
                ensureActive()
                session = opened
                val page = opened.initial()
                val items = withContext(dispatchers.cpu) { page.items.distinctBy { it.remote?.assetId ?: it.id } }
                ensureActive()
                check(items.isNotEmpty()) { "No Photos timeline neighbors" }
                if (value.value?.id == start.id) {
                    val anchorIdentity = anchor.remote?.assetId ?: anchor.id
                    val exactAnchor = items.firstOrNull { (it.remote?.assetId ?: it.id) == anchorIdentity }
                    checkNotNull(exactAnchor) { "The held item is no longer available in the timeline" }
                    focus(exactAnchor.id)
                    value.value = start.copy(items = items, loading = false, newer = page.newer, older = page.older)
                }
            } catch (cancelled: CancellationException) { opened?.close(); throw cancelled }
            catch (_: Exception) {
                opened?.close()
                ensureActive()
                if (value.value?.id == start.id) { session = null; value.value = start.copy(loading = false, error = true) }
            }
        }
    }
    fun loadMore(position: Int) {
        val current = value.value ?: return
        if (current.loading || current.loadingMore || current.error) return
        val direction = when {
            position <= 5 && current.newer != null -> SequenceDirection.NEWER
            position >= current.items.size - 6 && current.older != null -> SequenceDirection.OLDER
            else -> return
        }
        load(direction)
    }
    private fun load(direction: SequenceDirection) {
        val current = value.value ?: return
        val active = session ?: return
        val cursor = (if (direction == SequenceDirection.NEWER) current.newer else current.older) ?: return
        value.value = current.copy(loadingMore = true, error = false, failedDirection = null)
        work = scope.launch {
            try {
                val page = active.page(direction, cursor)
                val items = withContext(dispatchers.cpu) {
                    (if (direction == SequenceDirection.NEWER) page.items + current.items else current.items + page.items)
                        .distinctBy { it.remote?.assetId ?: it.id }
                }
                ensureActive()
                if (value.value?.id != current.id) return@launch
                seen += direction to cursor
                val continuation = (if (direction == SequenceDirection.NEWER) page.newer else page.older)
                    ?.takeUnless { direction to it in seen }
                value.value = current.copy(items = items, loadingMore = false, error = false,
                    newer = if (direction == SequenceDirection.NEWER) continuation else current.newer,
                    older = if (direction == SequenceDirection.OLDER) continuation else current.older,
                    failedDirection = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                ensureActive()
                if (value.value?.id == current.id) value.value = current.copy(error = true, failedDirection = direction)
            }
        }
    }
    fun retry() {
        val current = value.value ?: return
        if (current.loading || current.loadingMore) return
        if (session == null) open(current.anchor, current.sourceId)
        else current.failedDirection?.let(::load)
    }
    /** Cancel reads while hidden, retaining loaded items and their source-owned cursors. */
    fun pause() {
        work?.cancel(); work = null
        val current = value.value ?: return
        if (current.loading) { session?.close(); session = null }
        if (current.loadingMore) value.value = current.copy(loadingMore = false)
    }
    fun resume() {
        val current = value.value ?: return
        if (current.loading && work?.isActive != true) open(current.anchor, current.sourceId)
    }
    override fun close() {
        work?.cancel(); work = null; session?.close(); session = null
        seen.clear(); value.value = null; focusedId = null
    }
}
