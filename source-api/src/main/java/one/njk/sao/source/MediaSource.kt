package one.njk.sao.source

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.InputStream

/** Network-capable catalog contract. A source exposes pages, never an unbounded library read. */
interface MediaSource {
    val changes: Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
    val capabilities: Flow<SourceCapabilities> get() = flowOf(SourceCapabilities())
    val identity: Flow<String> get() = flowOf("local")
    val advancedSearch: Flow<Boolean> get() = flowOf(false)
    val randomBrowse: Flow<Boolean> get() = flowOf(false)
    val sequenceBrowse: Flow<Boolean> get() = flowOf(false)
    /** Checks that an item still belongs to the active session (including composite sources). */
    suspend fun owns(item: MediaItem): Boolean = identity.first() == (item.remote?.sourceId ?: "local")
    suspend fun openSequence(anchor: MediaItem): MediaSequence = throw UnsupportedOperationException("Sequence browsing is unavailable")
    suspend fun searchModes(): Set<SearchMode> = setOf(SearchMode.CONTEXT)
    suspend fun people(name: String, page: Int = 1, expectedSourceId: String? = null): PeoplePage = PeoplePage(emptyList())
    suspend fun personPreview(person: SearchPerson): Any? = null
    suspend fun places(level: PlaceLevel, country: String? = null, state: String? = null, expectedSourceId: String? = null): List<String> = emptyList()
    suspend fun page(query: MediaQuery, cursor: String? = null): MediaPage
    suspend fun filters(): List<String>
    suspend fun preview(item: MediaItem): Any
    /** Local original for a focused video preview; no player receives account credentials. */
    suspend fun video(item: MediaItem): java.io.File? = null
    /** Caller owns the stream and reads/closes it on IO. Fetching remote bytes suspends. */
    suspend fun original(item: MediaItem): InputStream
    /** A decoder-compatible still image for the existing 512px export pipeline. */
    suspend fun raster(item: MediaItem): InputStream
}
