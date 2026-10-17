package one.njk.sao.immichprovider

import one.njk.sao.source.*

import one.njk.sao.immich.*

/** Explicit search modes map to one field; Context retains server ranking and page semantics. */
object ImmichQueryCompiler {
    fun filter(query: MediaQuery, sourceId: String, albumId: String?, tagIds: List<String>?): SearchFilter {
        val options = query.constraints.forSource(sourceId)
        val text = query.text.trim()
        val pattern = text.takeIf { it.isNotEmpty() }?.let {
            StringFilter(like = "%${it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%")
        }
        return SearchFilter(albumIds = albumId?.let { IdsFilter(any = listOf(it)) },
            tagIds = tagIds?.let { IdsFilter(any = it) },
            type = EnumFilter(`in` = when (options.media) {
                SearchMedia.ALL -> listOf("IMAGE", "VIDEO"); SearchMedia.IMAGES -> listOf("IMAGE"); SearchMedia.VIDEOS -> listOf("VIDEO")
            }), visibility = EnumFilter(`in` = listOf("timeline", "archive")), trashedAt = DateEqualityFilter(null),
            originalFileName = pattern.takeIf { query.mode == SearchMode.FILENAME },
            originalPath = pattern.takeIf { query.mode == SearchMode.PATH },
            description = pattern.takeIf { query.mode == SearchMode.DESCRIPTION },
            ocr = text.takeIf { it.isNotEmpty() && query.mode == SearchMode.OCR }?.let(::TextMatchFilter),
            personIds = options.people.takeIf { it.isNotEmpty() }?.let { IdsFilter(all = it.map(SearchPerson::id)) },
            country = options.country?.let { StringFilter(eq = it) },
            state = options.state?.let { StringFilter(eq = it) }, city = options.city?.let { StringFilter(eq = it) })
    }
    fun context(query: MediaQuery, sourceId: String, albumId: String?, tagIds: List<String>?, page: Int): PagedSmartSearch {
        val options = query.constraints.forSource(sourceId)
        return PagedSmartSearch(query.text.trim(), page, albumIds = albumId?.let(::listOf),
            tagIds = tagIds?.takeIf { it.size == 1 },
            personIds = options.people.takeIf { it.isNotEmpty() }?.map(SearchPerson::id),
            country = options.country, state = options.state, city = options.city,
            type = when (options.media) { SearchMedia.ALL -> null; SearchMedia.IMAGES -> "IMAGE"; SearchMedia.VIDEOS -> "VIDEO" })
    }
}
