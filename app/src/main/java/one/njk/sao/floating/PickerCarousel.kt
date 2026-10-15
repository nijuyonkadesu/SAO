package one.njk.sao.floating

import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.FullScreenCarouselStrategy
import com.google.android.material.carousel.HeroCarouselStrategy

internal data class PickerBrowseKey(val sourceId: String, val query: MediaQuery, val sequenceId: String?)

/** A carousel owns its list, layout and pending placement. Search stays mounted during sequence navigation. */
internal class PickerCarousel(val view: RecyclerView, val adapter: MediaCarouselAdapter,
    private val current: (PickerBrowseKey) -> Boolean,
    private val focused: (PickerBrowseKey) -> String?,
    private val focusChanged: (PickerBrowseKey, String?, Int) -> Unit,
    private val remember: () -> Unit, private val dragging: () -> Unit) : AutoCloseable {
    private val snap = CarouselSnapHelper(false)
    private var key: PickerBrowseKey? = null
    private var displayed: PickerBrowseKey? = null
    private var submittedItems: List<MediaItem>? = null
    private var committedGeneration = -1L
    private var generation = 0L
    private var placing = false
    private var needsPlacement = false
    private var wanted: String? = null
    private var recordingPending = false
    private var fullScreen = false
    private var closed = false

    init {
        view.layoutManager = manager(false)
        view.adapter = adapter
        snap.attachToRecyclerView(view)
        view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = recordFocus()
            override fun onScrollStateChanged(recyclerView: RecyclerView, state: Int) {
                adapter.scrolling = recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
                if (!adapter.scrolling) { recordFocus(); if (!placing && key?.let(current) == true) remember() }
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) dragging()
            }
        })
    }

    private fun manager(fullScreen: Boolean) = CarouselLayoutManager(
        if (fullScreen) FullScreenCarouselStrategy() else HeroCarouselStrategy()).apply {
        carouselAlignment = CarouselLayoutManager.ALIGNMENT_CENTER
    }

    fun render(state: PickerState) {
        val next = PickerBrowseKey(state.sourceId, state.query, state.sequenceId)
        if (!current(next)) return
        if (state.loading && state.sequenceId == null) {
            if (key?.sourceId != state.sourceId) clear()
            return
        }
        val changed = key != next
        val prepended = !changed && next.sequenceId != null && adapter.currentList.firstOrNull()?.id?.let {
            it != state.items.firstOrNull()?.id
        } == true
        if (changed || prepended) {
            wanted = focused(next)
            needsPlacement = true; placing = true
        }
        key = next
        // Loading/pagination emissions with the same list must not cancel its pending commit/placement.
        if (!changed && submittedItems === state.items) return
        submittedItems = state.items
        val version = ++generation
        adapter.submitList(state.items) {
            if (!closed && version == generation && key == next && current(next)) {
                displayed = next; committedGeneration = version
                if (needsPlacement) place(version, next)
            }
        }
    }

    private fun place(version: Long, browse: PickerBrowseKey) {
        if (adapter.currentList.isEmpty()) { placing = false; needsPlacement = false; return }
        val position = adapter.currentList.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
        adapter.focusedId = adapter.currentList[position].id
        placing = true
        view.stopScroll()
        view.scrollToPosition(position)
        // Read focus only after Material has rebuilt its keylines for the committed list and size.
        view.doOnPreDraw {
            if (!closed && version == generation && key == browse && current(browse)) {
                needsPlacement = false; placing = false
                recordFocus(); remember()
            }
        }
    }

    fun recordFocus() {
        val browse = key ?: return
        if (closed || placing || displayed != browse || !current(browse) || !view.isAttachedToWindow || !view.isShown) return
        if (view.isLayoutRequested || view.isComputingLayout) {
            if (!recordingPending) {
                recordingPending = true
                view.doOnPreDraw { recordingPending = false; if (key == browse) recordFocus() }
            }
            return
        }
        val manager = view.layoutManager ?: return
        val focal = snap.findSnapView(manager) ?: return
        val position = manager.getPosition(focal)
        val id = adapter.currentList.getOrNull(position)?.id ?: return
        adapter.focusedId = id
        focusChanged(browse, id, position)
    }

    fun useFullScreen(value: Boolean) {
        if (value == fullScreen) return
        recordFocus()
        fullScreen = value
        wanted = key?.let(focused) ?: adapter.focusedId
        needsPlacement = true; placing = true
        view.layoutManager = manager(value)
        key?.takeIf { it == displayed && committedGeneration == generation && current(it) }?.let { place(generation, it) }
    }

    fun pause() { adapter.cancelGestures(); view.stopScroll(); adapter.playing = false }
    fun clear() {
        pause(); ++generation; key = null; displayed = null
        placing = false; needsPlacement = false; wanted = null; submittedItems = null
        adapter.focusedId = null; adapter.submitList(emptyList())
    }
    override fun close() { closed = true; ++generation; adapter.close(); snap.attachToRecyclerView(null); view.adapter = null }
}
