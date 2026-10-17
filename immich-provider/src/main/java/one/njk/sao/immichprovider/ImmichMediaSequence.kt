package one.njk.sao.immichprovider

import one.njk.sao.source.*

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import one.njk.sao.immich.*

/** Exact Photos ordering comes from the timeline, including local-day and filename tie breaks. */
class ImmichMediaSequence(private val api: ImmichApi, private val albumId: String?,
    private val anchor: String, private val anchorBucket: Int, private val buckets: List<TimeBucket>,
    private val dispatchers: FloatingDispatchers, private val mapAsset: (ImmichAsset) -> MediaItem) : MediaSequence {
    fun descriptor() = org.json.JSONObject().put("anchor",anchor).put("bucket",anchorBucket)
        .put("buckets",one.njk.sao.source.SourceWire.strings(buckets.map { it.timeBucket }))
    private val lock = Any()
    private var closed = false
    private var cached: Pair<Int, List<String>>? = null
    private suspend fun index(bucket: Int): List<String> {
        synchronized(lock) { check(!closed); cached?.takeIf { it.first == bucket }?.let { return it.second } }
        val ids = run { TimelineIndex.read(api.timeBucket(buckets[bucket].timeBucket, albumId)) }
        synchronized(lock) { check(!closed); cached = bucket to ids }
        return ids
    }
    override suspend fun initial(): SequencePage = withContext(dispatchers.io) {
        val ids = index(anchorBucket)
        val position = ids.indexOf(anchor)
        check(position >= 0) { "This item is not in the Photos timeline (it may be stacked or removed)" }
        val start = maxOf(0, position - 20)
        val end = minOf(ids.size, position + 21)
        window(anchorBucket, ids, start, end)
    }
    override suspend fun page(direction: SequenceDirection, cursor: String): SequencePage = withContext(dispatchers.io) {
        val parts = cursor.split(':'); require(parts.size == 2)
        var bucket = parts[0].toInt(); var offset = parts[1].toInt()
        require(bucket in buckets.indices && offset >= -1)
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = index(bucket)
            if (offset == -1) { require(direction == SequenceDirection.NEWER); offset = ids.size }
            require(offset <= ids.size)
            val start = if (direction == SequenceDirection.NEWER) maxOf(0, offset - 40) else offset
            val end = if (direction == SequenceDirection.NEWER) offset else minOf(ids.size, offset + 40)
            val result = window(bucket, ids, start, end)
            val next = if (direction == SequenceDirection.NEWER) result.newer else result.older
            if (result.items.isNotEmpty() || next == null) return@withContext result
            val following = next.split(':'); bucket = following[0].toInt(); offset = following[1].toInt()
        }
        @Suppress("UNREACHABLE_CODE") error("No timeline page")
    }
    private suspend fun window(bucket: Int, ids: List<String>, start: Int, end: Int): SequencePage {
        val wanted = ids.subList(start, end)
        val assets = if (wanted.isEmpty()) emptyList() else run {
            api.search(MetadataSearch(SearchFilter(
                albumIds = albumId?.let { IdsFilter(any = listOf(it)) },
                visibility = EnumFilter(eq = "timeline"), trashedAt = DateEqualityFilter(null),
                or = wanted.map { SearchBranch(id = IdEqualityFilter(it)) }), size = wanted.size)).assets.items
        }
        return withContext(dispatchers.cpu) {
            currentCoroutineContext().ensureActive()
            val mapped = assets.filter { it.type in setOf("IMAGE", "VIDEO") && it.visibility == "timeline" }.associateBy { it.id }
            val items = wanted.mapNotNull { mapped[it]?.let(mapAsset) }
            SequencePage(items,
                if (start > 0) "$bucket:$start" else if (bucket > 0) "${bucket - 1}:-1" else null,
                if (end < ids.size) "$bucket:$end" else if (bucket < buckets.lastIndex) "${bucket + 1}:0" else null)
        }
    }
    override fun close() { synchronized(lock) { closed = true; cached = null } }
}
