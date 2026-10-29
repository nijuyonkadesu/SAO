package one.njk.sao.floating

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import one.njk.sao.R
import one.njk.sao.gif.GifskiEncoder
import one.njk.sao.source.FloatingDispatchers
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Host-owned, quality-first GIF export. Neither source APIs nor original bytes are changed. */
@Singleton
class VideoGifTranscoder @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers=FloatingDispatchers()) {
    suspend fun gif(input: File,output: File) = withContext(dispatchers.io) {
        // Native workers inherit background priority without changing a shared coroutine
        // pool thread's priority. This thread exists only for the explicit conversion.
        val thread=HandlerThread("SAO GIF conversion",Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val worker=Handler(thread.looper).asCoroutineDispatcher()
        try {
            val completed=withTimeoutOrNull(90_000) {
                supervisorScope {
                    val signal=Any()
                    var running: GifskiEncoder?=null
                    val conversion=async(worker) {
                        GifskiEncoder(output,MediaExportStore.MAX_BYTES).use { encoder ->
                            synchronized(signal) { running=encoder }
                            try {
                                val cancellation=currentCoroutineContext()
                                cancellation.ensureActive()
                                val video=VideoFrameDecoder(context).decode(input,cancellation) { index,width,height,rowBytes,rgba,ptsUs ->
                                    encoder.add(index,width,height,rowBytes,rgba,ptsUs/1_000_000.0)
                                }
                                encoder.finish()
                                GifContainer.finish(output,video.durationUs,video.width,video.height)
                            } finally { synchronized(signal) { running=null } }
                        }
                    }
                    try { conversion.await() }
                    catch(error: Throwable) {
                        // Native add/finish can block on bounded encoder queues. Signal their
                        // callbacks immediately, then join before releasing input or deleting output.
                        synchronized(signal) { running?.cancel() }
                        withContext(NonCancellable) { conversion.join() }
                        throw error
                    }
                }
                true
            }
            if(completed!=true) throw MediaExportException(R.string.media_export_gif_timeout)
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: MediaExportException) { throw error }
        catch(error: VideoFrameDecoder.DecodeException) { throw MediaExportException(when(error.reason) {
            VideoFrameDecoder.Failure.MEMORY -> R.string.media_export_gif_memory
            VideoFrameDecoder.Failure.DECODE -> R.string.media_export_decode_failed
            VideoFrameDecoder.Failure.FORMAT -> R.string.media_export_gif_unavailable
        },error) }
        catch(error: java.io.IOException) { throw MediaExportException(R.string.media_export_gif_unavailable,error) }
        catch(error: LinkageError) { throw MediaExportException(R.string.media_export_gif_unavailable,error) }
        finally { thread.quitSafely(); thread.join() }
    }
}
