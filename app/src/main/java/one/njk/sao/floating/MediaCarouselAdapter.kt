package one.njk.sao.floating

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.google.android.material.color.MaterialColors
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
    private val select: (MediaItem) -> Unit) :
    ListAdapter<MediaItem, MediaCarouselAdapter.Holder>(object : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
    }) {
    private val attached = mutableSetOf<Holder>()
    private var player: ExoPlayer? = null
    private var videoHolder: Holder? = null
    private var videoId: String? = null
    private var videoLoading: Job? = null
    private var videoVersion = 0L
    private var closed = false
    private var furthestBound = -1
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
        var item: MediaItem? = null
        var request: Disposable? = null
        var loading: Job? = null
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemFloatingMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
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
        holder.binding.root.contentDescription = "${item.title}, ${item.tags.joinToString(", ")}${kind?.let { ", ${holder.binding.root.context.getString(it)}" }.orEmpty()}"
        load(holder)
        holder.binding.root.setOnClickListener { select(item) }
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
        holder.binding.preview.setImageDrawable(null)
        holder.loading = scope.launch {
            try {
                val data = source.preview(item)
                if (holder.item?.id != item.id) return@launch
                holder.request = loader.enqueue(ImageRequest.Builder(holder.binding.preview.context).data(data).size(1024, 1024)
                    .target(holder.binding.preview).listener(onSuccess = { _, _ -> decorate(holder) }).build())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (holder.item?.id == item.id) holder.binding.preview.setImageResource(one.njk.sao.R.drawable.ic_picker_media) }
        }
    }
    private fun decorate(holder: Holder) {
        val item = holder.item ?: return
        holder.binding.apply {
            val selected = item.id == selectedId
            root.isSelected = selected; check.isVisible = selected
            card.strokeWidth = if (selected) (2 * root.resources.displayMetrics.density).toInt() else 0
            card.strokeColor = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
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
                val file = source.video(item) ?: return@launch
                if (version != videoVersion || !playing || scrolling || holder !in attached || holder.item?.id != item.id) return@launch
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
                created.setMediaItem(PlayerMediaItem.Builder().setUri(Uri.fromFile(file)).setMimeType(item.mimeType).build())
                created.prepare(); created.play()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (version == videoVersion) stopVideo() }
        }
    }
    private fun stopVideo() {
        videoVersion++; videoLoading?.cancel(); videoLoading = null
        videoHolder?.binding?.videoPreview?.let { it.player = null; it.isVisible = false }
        videoHolder = null; videoId = null
        val old = player; player = null; old?.release()
    }
    fun close() { closed = true; stopVideo() }
    override fun onViewAttachedToWindow(holder: Holder) { attached += holder; if (holder.request == null && holder.loading?.isActive != true) load(holder); decorate(holder); syncVideo() }
    override fun onViewDetachedFromWindow(holder: Holder) {
        attached -= holder; (holder.binding.preview.drawable as? Animatable)?.stop()
        if (videoHolder === holder) stopVideo()
        holder.request?.dispose(); holder.request = null; holder.binding.preview.setImageDrawable(null)
        holder.loading?.cancel(); holder.loading = null
        syncVideo()
    }
    override fun onViewRecycled(holder: Holder) {
        if (videoHolder === holder) stopVideo()
        attached -= holder; holder.request?.dispose(); holder.request = null; holder.item = null
        holder.loading?.cancel(); holder.loading = null
        holder.binding.preview.setImageDrawable(null)
        holder.binding.videoPreview.player = null; holder.binding.videoPreview.isVisible = false
    }
}
