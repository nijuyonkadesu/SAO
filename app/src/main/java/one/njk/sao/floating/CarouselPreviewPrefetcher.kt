package one.njk.sao.floating

import androidx.annotation.MainThread
import kotlinx.coroutines.*
import one.njk.sao.source.MediaItem
import one.njk.sao.source.MediaIdentity
import one.njk.sao.source.identityKey

/** A small moving window of host-cached previews, owned by the visible browser. */
@MainThread
internal class CarouselPreviewPrefetcher(private val scope: CoroutineScope,
    private val preview: suspend (MediaItem) -> Unit) {
    private data class PreviewKey(val identity: MediaIdentity, val variant: String)
    private var browse: PickerBrowseKey? = null
    private var position = 0
    private var direction = 1
    private var failed = false
    private var wanted = emptyList<Pair<PreviewKey,MediaItem>>()
    private val jobs = linkedMapOf<PreviewKey,Job>()
    private val completed = mutableSetOf<PreviewKey>()

    fun update(key: PickerBrowseKey,items: List<MediaItem>,index: Int) {
        if(browse!=key) {
            pause(); completed.clear(); failed=false; direction=1; position=index; browse=key
        }
        if(index!=position) direction=if(index<position) -1 else 1
        position=index
        wanted=(0 until 4).mapNotNull { offset -> items.getOrNull(index+offset*direction) }.mapNotNull { item ->
            val remote=item.remote ?: return@mapNotNull null
            val variant=remote.cacheKeys["preview"] ?: return@mapNotNull null
            // GIFs/original fallbacks can be large. Warm only a distinct display rendition;
            // video thumbnails are fine, but playback/original files are never requested here.
            if(variant==(remote.cacheKeys["original"] ?: "original")) null else PreviewKey(item.identityKey,variant) to item
        }
        val ids=wanted.map { it.first }.toSet()
        completed.retainAll(ids)
        jobs.keys.filterNot { it in ids }.forEach { jobs.remove(it)?.cancel() }
        pump()
    }
    private fun pump() {
        if(failed) return
        for((id,item) in wanted) {
            if(jobs.size>=2) break
            if(id in jobs || id in completed) continue
            val job=scope.launch(start=CoroutineStart.LAZY) {
                try { preview(item); completed+=id }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) {
                    // Foreground loads retain their ordinary retry action. Speculative work
                    // stops for this browse rather than repeatedly hitting a failing source.
                    failed=true
                    jobs.filterKeys { it!=id }.values.forEach { it.cancel() }
                } finally {
                    if(jobs[id]===currentCoroutineContext()[Job]) { jobs.remove(id); pump() }
                }
            }
            jobs[id]=job; job.start()
        }
    }
    fun pause() { wanted=emptyList(); val old=jobs.values.toList(); jobs.clear(); old.forEach { it.cancel() } }
}
