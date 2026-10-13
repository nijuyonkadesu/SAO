package one.njk.sao.floating

import android.content.Context
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.njk.sao.immich.*
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.flowOf

/** One Immich profile session. Created on demand, closed when the active profile changes. */
class ImmichMediaSource(private val context: Context, private val config: ImmichConfig,
    private val sourceId: String, private val dispatchers: FloatingDispatchers) : ManagedMediaSource {
    private val client = ImmichClient(config.connection)
    private var cachedTags: List<ImmichTag>? = null
    private val tagsMutex = Mutex()
    private val downloads = Semaphore(3)
    private val fileLocks = Array(32) { Mutex() }
    private val cacheMaintenance = Mutex()
    private var lastCacheTrim = 0L
    override val advancedSearch = flowOf(true)
    override val randomBrowse = flowOf(true)
    override fun close() = client.close()
    override suspend fun searchModes(): Set<SearchMode> = withContext(dispatchers.io) {
        val features = ReadRetry.run { client.api.features() }
        buildSet {
            add(SearchMode.FILENAME); add(SearchMode.DESCRIPTION); add(SearchMode.PATH)
            if (features.smartSearch) add(SearchMode.CONTEXT)
            if (features.ocr != false) add(SearchMode.OCR)
        }
    }
    override suspend fun filters(): List<String> {
        val tags = tags()
        return withContext(dispatchers.cpu) { tags.map { it.name }.filter(::isEmojiTag).distinct() }
    }
    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage = withContext(dispatchers.io) {
        if (query.randomRequest != null && cursor != null) return@withContext MediaPage(emptyList())
        val api = client.api
        val availableTags = tags()
        val tagIds = query.filter?.let { selected -> availableTags.filter {
            MediaSearch.normalize(it.name) == MediaSearch.normalize(selected)
        }.map { it.id } }
        if (tagIds != null && tagIds.isEmpty()) return@withContext MediaPage(emptyList())
        val smart = query.text.isNotBlank() && query.mode == SearchMode.CONTEXT
        val filter = ImmichQueryCompiler.filter(if (query.randomRequest == null) query else query.copy(text = ""), sourceId, config.albumId, tagIds)
        val (assets, next) = if (query.randomRequest != null) {
            ReadRetry.run { api.randomSearch(RandomSearch(filter)) } to null
        } else if (smart) smartPage(query, tagIds, filter, cursor) else {
            val result = ReadRetry.run { api.search(MetadataSearch(filter, cursor)).assets }
            result.items to result.nextCursor?.takeIf { it.isNotBlank() && it != cursor }
        }
        withContext(dispatchers.cpu) { MediaPage(assets.filter { it.type in setOf("IMAGE", "VIDEO") }.map { asset ->
            val mime = mediaMime(asset)
            val gif = mime == "image/gif"
            val motion = asset.type == "VIDEO" || gif || mime.startsWith("video/")
            MediaItem("immich_${digest(sourceId).take(16)}_${digest(asset.id + asset.updatedAt + asset.isEdited).take(32)}",
                asset.exifInfo?.description?.takeIf { it.isNotBlank() } ?: asset.originalFileName, "", mime,
                asset.tags.map { it.value }, asset.tags.map { it.name }.filter(::isEmojiTag), motion,
                RemoteMedia(sourceId, asset.id, asset.isEdited && !motion))
        }, next) }
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
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = ReadRetry.run { client.api.pagedSmartSearch(
                ImmichQueryCompiler.context(query, sourceId, config.albumId, tagIds, page)).assets }
            val nextPage = result.nextPage?.takeIf { it.isNotBlank() }?.let { value ->
                requireNotNull(value.toIntOrNull()?.takeIf { it > page }) { "Invalid smart-search next page" }
            }
            check(result.items.isNotEmpty() || nextPage == null) { "Empty smart-search page with a continuation" }
            val candidates = withContext(dispatchers.cpu) {
                result.items.filter { it.type in setOf("IMAGE", "VIDEO") && it.visibility in setOf("timeline", "archive") }
            }
            val assets = if (tagIds != null && tagIds.size > 1 && candidates.isNotEmpty()) {
                val matching = ReadRetry.run { client.api.search(MetadataSearch(filter.copy(
                    or = candidates.map { SearchBranch(id = IdEqualityFilter(it.id)) }), size = candidates.size)).assets.items }
                val ids = withContext(dispatchers.cpu) { matching.map { it.id }.toSet() }
                withContext(dispatchers.cpu) { candidates.filter { it.id in ids } }
            } else candidates
            // Skip unsupported/hidden-only pages without falsely displaying an empty search.
            // Requests are serial, cancellable, and stop as soon as a usable page is available.
            if (assets.isNotEmpty() || nextPage == null) return assets to nextPage?.let { "$SMART_PAGE$it" }
            page = nextPage
        }
    }
    override suspend fun original(item: MediaItem): InputStream {
        return cached(item, false).inputStream()
    }
    override suspend fun people(name: String, page: Int, expectedSourceId: String?): PeoplePage = withContext(dispatchers.io) {
        check(expectedSourceId == null || expectedSourceId == sourceId) { "Filter belongs to a different source" }
        if (name.isNotBlank()) PeoplePage(ReadRetry.run { client.api.searchPeople(name.trim()) }.map { SearchPerson(it.id, it.name, sourceId) })
        else ReadRetry.run { client.api.people(page) }.let { result ->
            PeoplePage(result.people.map { SearchPerson(it.id, it.name, sourceId) }, if (result.hasNextPage) page + 1 else null)
        }
    }
    override suspend fun places(level: PlaceLevel, country: String?, state: String?, expectedSourceId: String?) = withContext(dispatchers.io) {
        check(expectedSourceId == null || expectedSourceId == sourceId) { "Filter belongs to a different source" }
        ReadRetry.run { client.api.places(level.name.lowercase(java.util.Locale.ROOT), country, state) }.filterNotNull().distinct()
    }
    override suspend fun personPreview(person: SearchPerson): Any = withContext(dispatchers.io) {
        check(person.sourceId == sourceId) { "Person belongs to a different source" }
        val directory = File(context.cacheDir, "immich/${digest(sourceId).take(16)}").apply { mkdirs() }
        val file = File(directory, "person-${digest(person.id)}")
        fileLocks[(file.name.hashCode() and Int.MAX_VALUE) % fileLocks.size].withLock { downloads.withPermit {
            if (!file.isFile) { ReadRetry.run { client.downloadPerson(person.id, file) }; trimCache(file) }
        } }
        file
    }
    override suspend fun raster(item: MediaItem): InputStream {
        // JPEG previews can lose transparency. Preserve native PNG/WebP sticker input.
        if (item.mimeType in setOf("image/png", "image/webp")) return original(item)
        // Immich renders camera orientation and formats such as RAW/TIFF into its preview.
        // Downloading a multi-megapixel original is unnecessary for a 512px rendition.
        return cached(item, true).inputStream()
    }
    override suspend fun preview(item: MediaItem): Any {
        if (item.remote == null) return item.preview
        // GIF originals preserve animation; Immich thumbnails flatten GIFs.
        return cached(item, item.isVideo || !item.animated)
    }
    override suspend fun video(item: MediaItem): File? =
        if (item.isVideo && item.remote != null) cached(item, false) else null
    private suspend fun cached(item: MediaItem, preview: Boolean): File = withContext(dispatchers.io) {
        val ref = requireNotNull(item.remote)
        check(ref.sourceId == sourceId) { "Media belongs to a different connection" }
        val directory = File(context.cacheDir, "immich/${digest(ref.sourceId).take(16)}").apply { mkdirs() }
        val file = File(directory, "${item.id}-${if (preview) "preview" else "original"}")
        fileLocks[(file.name.hashCode() and Int.MAX_VALUE) % fileLocks.size].withLock { downloads.withPermit {
            if (!file.isFile) {
                ReadRetry.run { client.download(ref.assetId, file, preview, ref.edited,
                    maxBytes = if (preview) 8L * 1024 * 1024 else 64L * 1024 * 1024) }
                trimCache(file)
            }
        } }
        file
    }
    private fun isEmojiTag(value: String) = value.codePoints().anyMatch {
        it in 0x1f000..0x1faff || it in 0x2600..0x27ff || it == 0x00a9 || it == 0x00ae
    }
    private suspend fun tags(): List<ImmichTag> = withContext(dispatchers.io) { tagsMutex.withLock {
        cachedTags ?: ReadRetry.run { client.api.tags() }.also { cachedTags = it }
    } }
    private suspend fun trimCache(keep: File) = cacheMaintenance.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCacheTrim < 30_000) return@withLock
        lastCacheTrim = now
        val files = File(context.cacheDir, "immich").walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".pending") }.toList().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val recent = System.currentTimeMillis() - 10 * 60_000
        for (candidate in files) {
            if (total <= 192L * 1024 * 1024) break
            // Recently requested files may still be decoding in Coil. Exports are separate.
            if (candidate != keep && candidate.lastModified() < recent) {
                val length = candidate.length(); if (candidate.delete()) total -= length
            }
        }
    }
    private companion object { const val SMART_PAGE = "immich-smart-page:" }
}
