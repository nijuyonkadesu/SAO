package one.njk.sao.floating

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import one.njk.sao.source.*

/** Same progressive playback and software fallback for cards and the full-size viewer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object MediaPlaybackRenderer {
    suspend fun play(view: PlayerView, source: MediaSource, item: MediaItem, original: Boolean = false,
        muted: Boolean = true, startPosition: Long = 0, position: (Long) -> Unit = {}, firstFrame: () -> Unit) {
        source.withPlayback(item, original) { media -> withContext(Dispatchers.Main.immediate) {
            val owner = currentCoroutineContext().job
            val factory = PlaybackCache.factory(view.context, media, owner)
            if (!item.isVideo) {
                SoftwareVideoPreview.play(view, { PlaybackDecoderInput(factory, media.uri, owner) }, firstFrame)
                return@withContext
            }
            val io=Job(owner)
            var activePlayer: ExoPlayer?=null
            val error = try {
                val hardware=PlaybackCache.factory(view.context,media,io)
                val failure = CompletableDeferred<PlaybackException>()
                val player = ExoPlayer.Builder(view.context, DefaultRenderersFactory(view.context).setEnableDecoderFallback(true))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(hardware).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)))
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 5000, 250, 500)
                        .setTargetBufferBytes(8 * 1024 * 1024).build()).build().also { activePlayer=it }
                player.volume = if (muted) 0f else 1f
                player.repeatMode = Player.REPEAT_MODE_ONE
                if (muted) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = firstFrame()
                    override fun onPlayerError(error: PlaybackException) { failure.complete(error) }
                })
                view.player = player
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(media.uri))
                player.seekTo(startPosition); player.prepare(); player.play()
                failure.await()
            } finally {
                // Abort blocked provider IO before Media3 waits for its playback thread to stop.
                // Decoder fallback receives a new read lifetime over the same cached spans.
                withContext(NonCancellable) { io.cancelAndJoin() }
                activePlayer?.let { player ->
                    position(player.currentPosition)
                    if (view.player === player) view.player = null
                    player.release()
                }
            }
            if (error.errorCode !in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)) throw error
            // IO/auth failures never trigger another fetch or a decoder retry.
            SoftwareVideoPreview.play(view, { PlaybackDecoderInput(factory, media.uri, owner) }, firstFrame)
        } }
    }
}
