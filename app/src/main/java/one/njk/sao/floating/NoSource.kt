package one.njk.sao.floating

import one.njk.sao.source.*

import kotlinx.coroutines.flow.flowOf

/** No built-in library. Installing and configuring a source supplies the catalog. */
internal object NoSource : MediaSource {
    override val identity = flowOf(LOCAL_SOURCE_ID)
    override suspend fun owns(item: MediaItem) = false
    override suspend fun page(query: MediaQuery, cursor: String?) = MediaPage(emptyList())
    override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview) -> T): T = throw SourceUnsupportedException(SourceFeature.SOURCE)
    override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
        block: suspend (MediaHandle) -> T): T = throw SourceUnsupportedException(SourceFeature.SOURCE)
}
