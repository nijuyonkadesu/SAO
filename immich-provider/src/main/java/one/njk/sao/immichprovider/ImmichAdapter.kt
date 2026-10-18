package one.njk.sao.immichprovider

import one.njk.sao.source.*

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.njk.sao.immich.*

/** One requested Immich operation; the extension closes its client after producing the response. */
class ImmichAdapter(private val config: ImmichConfig, private val sourceId: String,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers()) : AutoCloseable {
    private val client = ImmichClient(config.connection, retryReadConnections = false)
    private var cachedTags: List<ImmichTag>? = null
    private val tagsMutex = Mutex()
    suspend fun openSequence(anchor: MediaItem): MediaSequence = withContext(dispatchers.io) {
        val ref = requireNotNull(anchor.remote)
        check(ref.sourceId == sourceId) { "Media belongs to a different source" }
        val asset = run { client.api.asset(ref.assetId) }
        check(asset.visibility == "timeline") { "This item is not in the Photos timeline" }
        val month = requireNotNull(asset.localDateTime).substringBefore('T').substringBeforeLast('-')
        val buckets = run { client.api.timeBuckets(config.albumId) }
        val bucket = buckets.indexOfFirst { it.timeBucket.substringBefore('T').substringBeforeLast('-') == month }
        check(bucket >= 0) { "This item is not in the Photos timeline" }
        ImmichMediaSequence(client.api, config.albumId, asset.id, bucket, buckets, dispatchers, ::mapAsset)
    }
    override fun close() = client.close()
    suspend fun searchModes(): Set<SearchMode> = withContext(dispatchers.io) {
        val features = run { client.api.features() }
        buildSet {
            add(SearchMode.FILENAME); add(SearchMode.DESCRIPTION); add(SearchMode.PATH)
            if (features.smartSearch) add(SearchMode.CONTEXT)
            if (features.ocr != false) add(SearchMode.OCR)
        }
    }
    suspend fun filters(): List<String> {
        val tags = tags()
        return withContext(dispatchers.cpu) { tags.map { it.name }.filter(::isEmojiTag).distinct() }
    }
    suspend fun page(query: MediaQuery, cursor: String?): MediaPage = withContext(dispatchers.io) {
        if (query.randomRequest != null && cursor != null) return@withContext MediaPage(emptyList())
        val api = client.api
        val tagIds = query.filter?.let { selected -> tags().filter {
            MediaSearch.normalize(it.name) == MediaSearch.normalize(selected)
        }.map { it.id } }
        if (tagIds != null && tagIds.isEmpty()) return@withContext MediaPage(emptyList())
        val smart = query.text.isNotBlank() && query.mode == SearchMode.CONTEXT
        val filter = ImmichQueryCompiler.filter(if (query.randomRequest == null) query else query.copy(text = ""), sourceId, config.albumId, tagIds)
        val (assets, next) = if (query.randomRequest != null) {
            run { api.randomSearch(RandomSearch(filter)) } to null
        } else if (smart) smartPage(query, tagIds, filter, cursor) else {
            val result = run { api.search(MetadataSearch(filter, cursor)).assets }
            result.items to result.nextCursor?.takeIf { it.isNotBlank() && it != cursor }
        }
        withContext(dispatchers.cpu) { MediaPage(assets.filter { it.type in setOf("IMAGE", "VIDEO") }.map(::mapAsset), next) }
    }
    fun mapAsset(asset: ImmichAsset): MediaItem {
        val mime = mediaMime(asset)
        val motion = asset.type == "VIDEO" || mime == "image/gif" || mime.startsWith("video/")
        return MediaItem("immich_${digest(sourceId).take(16)}_${digest(asset.id + asset.updatedAt + asset.isEdited).take(32)}",
            asset.exifInfo?.description?.takeIf { it.isNotBlank() } ?: asset.originalFileName, "", mime,
            asset.tags.map { it.value }, asset.tags.map { it.name }.filter(::isEmojiTag), motion,
            RemoteMedia(sourceId, asset.id, asset.isEdited && !motion, asset.createdAt, mapOf(
                "original" to "original", "video" to "original",
                "preview" to if (motion && !mime.startsWith("video/")) "original" else "preview",
                "raster" to if (mime in setOf("image/png","image/webp")) "original" else "preview")), filename = asset.originalFileName)
    }
    private fun mediaMime(asset: ImmichAsset): String {
        val reported = asset.originalMimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (!reported.isNullOrBlank() && reported !in setOf("application/octet-stream", "binary/octet-stream", "application/binary"))
            return reported
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            asset.originalFileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
            ?: if (asset.type == "VIDEO") "video/mp4" else "image/jpeg"
    }
    private suspend fun smartPage(query: MediaQuery, tagIds: List<String>?, filter: SearchFilter,
        cursor: String?): Pair<List<ImmichAsset>, String?> {
        var page = if (cursor == null) 1 else {
            require(cursor.startsWith(SMART_PAGE)) { "Invalid smart-search continuation" }
            requireNotNull(cursor.removePrefix(SMART_PAGE).toIntOrNull()?.takeIf { it > 1 })
        }
        // Legacy Context supports pagination; structured Smart Search currently does not.
        // A single tag has identical semantics. Multiple matching leaf names require ANY,
        // whereas flat tagIds means ALL: resolve that rare case through a bounded ID lookup.
        run {
            currentCoroutineContext().ensureActive()
            val result = run { client.api.pagedSmartSearch(
                ImmichQueryCompiler.context(query, sourceId, config.albumId, tagIds, page)).assets }
            val nextPage = result.nextPage?.takeIf { it.isNotBlank() }?.let { value ->
                requireNotNull(value.toIntOrNull()?.takeIf { it > page }) { "Invalid smart-search next page" }
            }
            check(result.items.isNotEmpty() || nextPage == null) { "Empty smart-search page with a continuation" }
            val candidates = withContext(dispatchers.cpu) {
                result.items.filter { it.type in setOf("IMAGE", "VIDEO") && it.visibility in setOf("timeline", "archive") }
            }
            val assets = if (tagIds != null && tagIds.size > 1 && candidates.isNotEmpty()) {
                val matching = run { client.api.search(MetadataSearch(filter.copy(
                    or = candidates.map { SearchBranch(id = IdEqualityFilter(it.id)) }), size = candidates.size)).assets.items }
                val ids = withContext(dispatchers.cpu) { matching.map { it.id }.toSet() }
                withContext(dispatchers.cpu) { candidates.filter { it.id in ids } }
            } else candidates
            // Return one page; SAO advances empty filtered pages using the opaque continuation.
            return assets to nextPage?.let { "$SMART_PAGE$it" }
        }
    }
    suspend fun people(name: String, page: Int, expectedSourceId: String?): PeoplePage = withContext(dispatchers.io) {
        check(expectedSourceId == null || expectedSourceId == sourceId) { "Filter belongs to a different source" }
        if (name.isNotBlank()) PeoplePage(run { client.api.searchPeople(name.trim()) }.map { SearchPerson(it.id, it.name, sourceId) })
        else run { client.api.people(page) }.let { result ->
            PeoplePage(result.people.map { SearchPerson(it.id, it.name, sourceId) }, if (result.hasNextPage) page + 1 else null)
        }
    }
    suspend fun places(level: PlaceLevel, country: String?, state: String?, expectedSourceId: String?) = withContext(dispatchers.io) {
        check(expectedSourceId == null || expectedSourceId == sourceId) { "Filter belongs to a different source" }
        run { client.api.places(level.name.lowercase(java.util.Locale.ROOT), country, state) }.filterNotNull().distinct()
    }
    fun mediaCall(id: String, kind: String, edited: Boolean) = client.mediaCall(id,kind,edited)
    fun reopenSequence(token: org.json.JSONObject): MediaSequence = ImmichMediaSequence(client.api, config.albumId,
        token.getString("anchor"), token.getInt("bucket"),
        SourceWire.strings(token.getJSONArray("buckets")).map { TimeBucket(it,0) }, dispatchers, ::mapAsset)
    private fun isEmojiTag(value: String) = value.codePoints().anyMatch {
        it in 0x1f000..0x1faff || it in 0x2600..0x27ff || it == 0x00a9 || it == 0x00ae
    }
    private suspend fun tags(): List<ImmichTag> = withContext(dispatchers.io) { tagsMutex.withLock {
        cachedTags ?: run { client.api.tags() }.also { cachedTags = it }
    } }
    private companion object { const val SMART_PAGE = "immich-smart-page:" }
}
