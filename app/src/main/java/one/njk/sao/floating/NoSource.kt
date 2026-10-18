package one.njk.sao.floating

import one.njk.sao.source.*

/** No built-in library. Installing and configuring a source supplies the catalog. */
internal object NoSource : MediaSource {
    override suspend fun page(query: MediaQuery,cursor: String?)=MediaPage(emptyList())
    override suspend fun filters()=emptyList<String>()
    override suspend fun searchModes()=emptySet<SearchMode>()
    override suspend fun preview(item: MediaItem): Any=error("No source selected")
    override suspend fun original(item: MediaItem): java.io.InputStream=error("No source selected")
    override suspend fun raster(item: MediaItem): java.io.InputStream=error("No source selected")
}
