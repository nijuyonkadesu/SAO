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
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.placeholder
import coil3.request.error
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.MediaItem as PlayerMediaItem
import one.njk.sao.databinding.ItemFloatingMediaBinding

@androidx.annotation.OptIn(UnstableApi::class)
class MediaCarouselAdapter(private val loader: ImageLoader, private val scope: CoroutineScope,
    private val source: MediaSource, private val prefetch: (Int) -> Unit = {},
    private val holdDelay: () -> Long = { HoldDragSelector.HOLD_MS },
    private val canHold: () -> Boolean = { false }, private val held: (MediaItem) -> Unit = {},
    private val home: Boolean = false, private val select: (MediaItem) -> Unit) :
    ListAdapter<MediaItem, MediaCarouselAdapter.Holder>(object : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
    }) {
    private val attached = mutableSetOf<Holder>()
    private var player: ExoPlayer? = null
    private var videoHolder: Holder? = null
    private var videoId: String? = null
    private var videoLoading: Job? = null
    private var videoRelease: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var videoVersion = 0L
    private var closed = false
    private var furthestBound = -1
    private var uploadFormatter: java.time.format.DateTimeFormatter? = null
    var showUploadTime = false
        set(value) { if (field != value) { field = value; furthestBound = -1; attached.forEach(::decorate) } }
    var selectedId: String? = null
        set(value) { if (field != value) { field = value; attached.forEach(::decorate) } }
    var focusedId: String? = null
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
        var request: Disposable? = null
        var loading: Job? = null
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
        if (videoHolder === holder) stopVideo()
        holder.request?.dispose()
        holder.loading?.cancel()
        val item = getItem(position); holder.item = item
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
            currentList.first().id == previousList.first().id && currentList[previousList.lastIndex].id == previousList.last().id
        if (!appended) furthestBound = -1
        // A short/overlapping page can leave the same viewport near the end without another
        // scroll callback. Continue prefetching as bindings approach the new end.
        if (furthestBound >= 0) prefetch(furthestBound)
    }
    private fun load(holder: Holder) {
        val item = holder.item ?: return
        holder.loading?.cancel()
        if (home) {
            holder.binding.preview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            holder.binding.preview.setImageResource(one.njk.sao.R.drawable.eager)
        } else holder.binding.preview.setImageDrawable(null)
        holder.loading = scope.launch {
            try {
                val data = source.preview(item).data
                if (holder.item?.id != item.id) return@launch
                holder.request = loader.enqueue(ImageRequest.Builder(holder.binding.preview.context).data(data).size(1024, 1024)
                    .apply { if (home) { placeholder(one.njk.sao.R.drawable.eager); error(one.njk.sao.R.drawable.eager) } }
                    .target(holder.binding.preview).listener(onSuccess = { _, _ ->
                        if (home) holder.binding.preview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        decorate(holder)
                    }).build())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (holder.item?.id == item.id) holder.binding.preview.setImageResource(
                if (home) one.njk.sao.R.drawable.eager else one.njk.sao.R.drawable.ic_picker_media) }
        }
    }
    private fun decorate(holder: Holder) {
        val item = holder.item ?: return
        holder.binding.apply {
            uploadedAt.isVisible = showUploadTime
            if (showUploadTime) {
                if (holder.uploadCaption == null) holder.uploadCaption = uploadCaption(root, item)
                uploadedAt.text = holder.uploadCaption
            }
            root.contentDescription = holder.description + if (showUploadTime) ", ${holder.uploadCaption}" else ""
            val selected = item.id == selectedId
            root.isSelected = selected; check.isVisible = selected
            (preview.drawable as? Animatable)?.let {
                if (playing && !scrolling && item.id == focusedId && holder in attached) it.start() else it.stop()
            }
        }
    }
    private fun syncVideo() {
        val holder = if (playing && !scrolling && !closed) attached.firstOrNull { it.item?.let { item -> item.id == focusedId && item.isVideo } == true } else null
        val item = holder?.item
        if (holder === videoHolder && item?.id == videoId) return
        stopVideo()
        if (holder == null || item == null) return
        videoHolder = holder; videoId = item.id
        val version = videoVersion
        videoLoading = scope.launch {
            try {
                // Scrolling has settled; allow any final layout/focus adjustment to finish.
                delay(200)
                source.withRendition(item, Rendition.PLAYBACK) { handle ->
                    val file = handle.requireFile()
                    if (version != videoVersion || !playing || scrolling || holder !in attached || holder.item?.id != item.id) return@withRendition
                    val created = ExoPlayer.Builder(holder.binding.root.context)
                        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 5000, 250, 500)
                            .setTargetBufferBytes(8 * 1024 * 1024).build()).build().apply {
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ONE
                        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                if (version == videoVersion && videoHolder === holder) holder.binding.videoPreview.alpha = 1f
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                if (version == videoVersion) stopVideo()
                            }
                        })
                    }
                    player = created
                    holder.binding.videoPreview.alpha = 0f
                    holder.binding.videoPreview.isVisible = true
                    holder.binding.videoPreview.player = created
                    // The source's playback rendition may use a different container from the original.
                    created.setMediaItem(PlayerMediaItem.fromUri(Uri.fromFile(file)))
                    created.prepare(); created.play()
                    val released = kotlinx.coroutines.CompletableDeferred<Unit>()
                    videoRelease = released
                    released.await()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (version == videoVersion) stopVideo() }
            finally { if (version == videoVersion) stopVideo() }
        }
    }
    private fun stopVideo() {
        videoVersion++
        videoHolder?.binding?.videoPreview?.let { it.player = null; it.isVisible = false }
        videoHolder = null; videoId = null
        val old = player; player = null; old?.release()
        videoRelease?.complete(Unit); videoRelease = null
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
    fun close() { closed = true; cancelGestures(); stopVideo() }
    override fun onViewAttachedToWindow(holder: Holder) { attached += holder; if (holder.request == null && holder.loading?.isActive != true) load(holder); decorate(holder); syncVideo() }
    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.hold?.cancel()
        attached -= holder; (holder.binding.preview.drawable as? Animatable)?.stop()
        if (videoHolder === holder) stopVideo()
        holder.request?.dispose(); holder.request = null; holder.binding.preview.setImageDrawable(null)
        holder.loading?.cancel(); holder.loading = null
        syncVideo()
    }
    override fun onViewRecycled(holder: Holder) {
        holder.hold?.cancel()
        if (videoHolder === holder) stopVideo()
        attached -= holder; holder.request?.dispose(); holder.request = null; holder.item = null
        holder.loading?.cancel(); holder.loading = null
        holder.binding.preview.setImageDrawable(null)
        holder.binding.videoPreview.player = null; holder.binding.videoPreview.isVisible = false
    }
}
