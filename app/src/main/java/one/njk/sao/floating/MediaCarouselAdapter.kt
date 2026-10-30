package one.njk.sao.floating

import one.njk.sao.source.*

import android.graphics.drawable.Animatable
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.media3.common.util.UnstableApi
import one.njk.sao.databinding.ItemFloatingMediaBinding

@androidx.annotation.OptIn(UnstableApi::class)
class MediaCarouselAdapter(private val loader: ImageLoader, private val scope: CoroutineScope,
    private val source: MediaSource, private val prefetch: (Int) -> Unit = {},
    private val holdDelay: () -> Long = { HoldDragSelector.HOLD_MS },
    private val canHold: () -> Boolean = { false }, private val held: (MediaItem) -> Unit = {},
    private val home: Boolean = false, private val select: (MediaItem) -> Unit) :
    ListAdapter<MediaItem, MediaCarouselAdapter.Holder>(object : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.identityKey == b.identityKey
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
    }) {
    private val attached = mutableSetOf<Holder>()
    private var videoHolder: Holder? = null
    private var videoId: MediaIdentity? = null
    private var videoLoading: Job? = null
    private val videoPlayback = Mutex()
    private var videoVersion = 0L
    private var closed = false
    private var furthestBound = -1
    private var uploadFormatter: java.time.format.DateTimeFormatter? = null
    var showUploadTime = false
        set(value) { if (field != value) { field = value; furthestBound = -1; attached.forEach(::decorate) } }
    var selectedId: MediaIdentity? = null
        set(value) { if (field != value) { field = value; attached.forEach(::decorate) } }
    var focusedId: MediaIdentity? = null
        set(value) {
            if (field != value) {
                field = value
                // Keep browsing focus current without doing playback work on each fling frame.
                if (!scrolling) { attached.forEach(::decorate); syncVideo() }
            }
        }
    var scrolling = false
        set(value) { if (field != value) { field = value; attached.forEach(::decorate); syncVideo() } }
    var playing = true
        set(value) { if (field != value) { field = value; attached.forEach(::decorate); syncVideo() } }
    class Holder(val binding: ItemFloatingMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        internal var hold: ResultHoldAction? = null
        var uploadCaption: String? = null
        var description: String = ""
        var item: MediaItem? = null
        var loading: Job? = null
        var previewVersion = 0L
        var loadingItem: MediaItem? = null
        var renderedItem: MediaItem? = null
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemFloatingMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also { holder ->
        if(home) holder.binding.apply {
            root.layoutParams.width = (300 * parent.resources.displayMetrics.density).toInt()
            preview.setPadding(0, 0, 0, 0)
            preview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            (videoPreview.layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, 0)
            videoPreview.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        holder.hold = ResultHoldAction(holder.binding.root, { canHold() && holder.item != null }, holdDelay,
            { holder.item?.let(select) }, { holder.item?.let(held) }, ignoreHold = { showUploadTime })
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.hold?.cancel()
        furthestBound = maxOf(furthestBound, position)
        prefetch(furthestBound)
        val item = getItem(position)
        if (videoHolder === holder && !samePreview(holder.item, item)) stopVideo()
        holder.item = item
        val kind = when { item.isVideo -> one.njk.sao.R.string.picker_video; item.animated -> one.njk.sao.R.string.picker_gif; else -> null }
        holder.binding.gifBadge.isVisible = item.hasMotion
        if (kind != null) {
            val label = holder.binding.root.context.getString(kind)
            if (holder.binding.gifBadge.text.toString() != label) holder.binding.gifBadge.text = label
        }
        holder.description = "${item.title}, ${item.tags.joinToString(", ")}${kind?.let { ", ${holder.binding.root.context.getString(it)}" }.orEmpty()}"
        holder.uploadCaption = null
        load(holder)
        decorate(holder)
        syncVideo()
    }
    override fun onCurrentListChanged(previousList: MutableList<MediaItem>, currentList: MutableList<MediaItem>) {
        val appended = previousList.isNotEmpty() && currentList.size > previousList.size &&
            currentList.first().identityKey == previousList.first().identityKey && currentList[previousList.lastIndex].identityKey == previousList.last().identityKey
        if (!appended) furthestBound = -1
        // A short/overlapping page can leave the same viewport near the end without another
        // scroll callback. Continue prefetching as bindings approach the new end.
        if (furthestBound >= 0) prefetch(furthestBound)
    }
    fun previewBounds(item: MediaItem): android.graphics.Rect? = attached.firstOrNull { it.item?.identityKey==item.identityKey }
        ?.binding?.preview?.let { view -> android.graphics.Rect().takeIf { view.getGlobalVisibleRect(it) } }
    internal fun previewFrame(item: MediaItem): MediaViewerFrame? {
        val holder=attached.firstOrNull { it.item?.identityKey==item.identityKey } ?: return null
        val video=holder.binding.videoPreview
        val surface=video.videoSurfaceView
        return MediaViewerFrame.capture(if(video.isVisible && video.alpha>0f && surface!=null) surface else holder.binding.preview)
    }
    private fun load(holder: Holder) {
        if (closed) return
        val item = holder.item ?: return
        // Cached RecyclerView children detach without being recycled. Their decoded image
        // stays valid, and metadata-only binds do not need another read or placeholder.
        if (samePreview(holder.renderedItem, item)) return
        if (holder.loading?.isActive == true && samePreview(holder.loadingItem, item)) return
        cancelPreview(holder)
        holder.loadingItem = item
        holder.renderedItem = null
        val version = holder.previewVersion
        val context = holder.binding.preview.context
        val memoryKey=previewMemoryKey(item)
        (holder.binding.preview.drawable as? Animatable)?.stop()
        // Recreated home holders can display an already decoded static preview in their first frame.
        if(home && !item.animated) loader.memoryCache?.get(MemoryCache.Key(memoryKey))?.let { cached ->
            holder.binding.preview.scaleType=android.widget.ImageView.ScaleType.CENTER_CROP
            holder.binding.preview.setImageDrawable(cached.image.asDrawable(context.resources))
            holder.renderedItem=item; holder.loadingItem=null
            return
        }
        if (home) {
            holder.binding.preview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            holder.binding.preview.setImageResource(one.njk.sao.R.drawable.eager)
        } else holder.binding.preview.setImageDrawable(null)
        holder.loading = scope.launch {
            try {
                // A ViewTarget can restart itself on reattach after this source lease ends.
                // Decode once, then let this holder's current job alone apply the result.
                val result = source.withPreview(item) { preview ->
                    loader.execute(ImageRequest.Builder(context).data(preview.data).memoryCacheKey(memoryKey).size(1024, 1024).build())
                }
                currentCoroutineContext().ensureActive()
                if (holder.previewVersion != version || !samePreview(holder.item, item) || closed) return@launch
                if (result is SuccessResult) {
                    holder.binding.preview.setImageDrawable(result.image.asDrawable(context.resources))
                    holder.renderedItem = item
                    if (home) holder.binding.preview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    decorate(holder)
                } else holder.binding.preview.setImageResource(
                    if (home) one.njk.sao.R.drawable.eager else one.njk.sao.R.drawable.ic_picker_media)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (holder.previewVersion == version && samePreview(holder.item, item) && !closed) holder.binding.preview.setImageResource(
                if (home) one.njk.sao.R.drawable.eager else one.njk.sao.R.drawable.ic_picker_media) }
            finally {
                if (holder.previewVersion == version && !closed) { holder.loading = null; syncVideo() }
            }
        }
    }
    private fun cancelPreview(holder: Holder) {
        holder.previewVersion++
        holder.loading?.cancel(); holder.loading = null
        holder.loadingItem = null
    }
    private fun samePreview(first: MediaItem?, second: MediaItem) = first != null &&
        first.identityKey == second.identityKey && first.id == second.id && first.asset == second.asset &&
        first.mimeType == second.mimeType && first.animated == second.animated &&
        first.remote?.edited == second.remote?.edited && first.remote?.cacheKeys == second.remote?.cacheKeys
    private fun decorate(holder: Holder) {
        val item = holder.item ?: return
        holder.binding.apply {
            uploadedAt.isVisible = showUploadTime
            if (showUploadTime) {
                if (holder.uploadCaption == null) holder.uploadCaption = uploadCaption(root, item)
                uploadedAt.text = holder.uploadCaption
            }
            root.contentDescription = holder.description + if (showUploadTime) ", ${holder.uploadCaption}" else ""
            val selected = item.identityKey == selectedId
            root.isSelected = selected; check.isVisible = selected
            (preview.drawable as? Animatable)?.let {
                if (playing && !scrolling && item.identityKey == focusedId && holder in attached) it.start() else it.stop()
            }
        }
    }
    private fun syncVideo() {
        val holder = if (playing && !scrolling && !closed) attached.firstOrNull { holder ->
            holder.item?.let { item -> item.identityKey == focusedId &&
                (item.isVideo || item.animated && holder.loading?.isActive != true && holder.binding.preview.drawable !is Animatable) } == true
        } else null
        val item = holder?.item
        if (holder === videoHolder && item?.identityKey == videoId) return
        stopVideo()
        if (holder == null || item == null) return
        videoHolder = holder; videoId = item.identityKey
        val version = videoVersion
        videoLoading = scope.launch { videoPlayback.withLock {
            if (version != videoVersion || closed) return@withLock
            try {
                // Scrolling has settled; allow any final layout/focus adjustment to finish.
                delay(200)
                if (version != videoVersion || !playing || scrolling || holder !in attached || !samePreview(holder.item, item)) return@withLock
                holder.binding.videoPreview.alpha = 0f
                holder.binding.videoPreview.isVisible = true
                MediaPlaybackRenderer.play(holder.binding.videoPreview, source, item) {
                    if (version == videoVersion && videoHolder === holder) holder.binding.videoPreview.alpha = 1f
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (version == videoVersion) {
                    if (one.njk.sao.BuildConfig.DEBUG) android.util.Log.i("SAO.VideoPreview",
                        "playback preparation failed; cause=${error.javaClass.name}")
                    stopVideo()
                }
            }
            finally { if (version == videoVersion) stopVideo() }
        } }
    }
    private fun stopVideo() {
        videoVersion++
        videoHolder?.binding?.videoPreview?.let { it.player = null; it.isVisible = false }
        videoHolder = null; videoId = null
        videoLoading?.cancel(); videoLoading = null
    }
    private fun uploadCaption(view: android.view.View, item: MediaItem): String {
        val date = item.remote?.uploadedAt?.let { raw -> runCatching {
            val formatter = uploadFormatter ?: java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
                .withLocale(view.resources.configuration.locales[0]).withZone(java.time.ZoneId.systemDefault())
                .also { uploadFormatter = it }
            formatter.format(java.time.Instant.parse(raw))
        }.getOrNull() }
        return date
            ?: view.context.getString(one.njk.sao.R.string.sequence_upload_unknown)
    }
    fun cancelGestures() { attached.forEach { it.hold?.cancel() } }
    fun close() {
        closed = true; cancelGestures(); stopVideo()
        attached.forEach { cancelPreview(it); (it.binding.preview.drawable as? Animatable)?.stop() }
    }
    override fun onViewAttachedToWindow(holder: Holder) { attached += holder; if (holder.loading == null) load(holder); decorate(holder); syncVideo() }
    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.hold?.cancel()
        attached -= holder; (holder.binding.preview.drawable as? Animatable)?.stop()
        if (videoHolder === holder) stopVideo()
        cancelPreview(holder)
        syncVideo()
    }
    override fun onViewRecycled(holder: Holder) {
        holder.hold?.cancel()
        if (videoHolder === holder) stopVideo()
        attached -= holder; holder.item = null
        cancelPreview(holder)
        holder.renderedItem = null
        holder.binding.preview.setImageDrawable(null)
        holder.binding.videoPreview.player = null; holder.binding.videoPreview.isVisible = false
    }
}
