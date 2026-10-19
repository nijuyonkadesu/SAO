package one.njk.sao.floating

import one.njk.sao.source.*

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
    private val centerChanged: () -> Unit,
    private val remember: () -> Unit, private val dragging: () -> Unit,
    private val home: Boolean = false) : AutoCloseable {
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
    private var centered: MediaItem? = null
    /** The committed focal card, independent of the last explicitly copied selection. */
    val centeredItem: MediaItem? get() = centered?.takeIf {
        !closed && !placing && key == displayed && key?.let(current) == true
    }
    private fun center(item: MediaItem?) {
        if (centered == item) return
        centered = item; centerChanged()
    }

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
        if (fullScreen) FullScreenCarouselStrategy() else HeroCarouselStrategy().apply {
            if(home) {
                // Leave two narrow previews around the prominent home card.
                val preview = 32 * view.resources.displayMetrics.density
                smallItemSizeMin = preview; smallItemSizeMax = preview
            }
        }).apply {
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
            center(null)
        }
        key = next
        // Loading/pagination emissions with the same list must not cancel its pending commit/placement.
        if (!changed && submittedItems === state.items) { recordFocus(); return }
        submittedItems = state.items
        val version = ++generation
        adapter.submitList(state.items) {
            if (!closed && version == generation && key == next && current(next)) {
                displayed = next; committedGeneration = version
                if (adapter.currentList.isEmpty()) center(null)
                if (needsPlacement) place(version, next) else recordFocus()
            }
        }
    }

    private fun place(version: Long, browse: PickerBrowseKey) {
        if (adapter.currentList.isEmpty()) { placing = false; needsPlacement = false; center(null); return }
        val position = adapter.currentList.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
        adapter.focusedId = adapter.currentList[position].id
        placing = true
        center(null)
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
        val item = adapter.currentList.getOrNull(position) ?: return
        val id = item.id
        adapter.focusedId = id
        center(item)
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
        center(null)
    }
    override fun close() { closed = true; centered = null; ++generation; adapter.close(); snap.attachToRecyclerView(null); view.adapter = null }
}
