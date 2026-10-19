package one.njk.sao.moeview.data

/** Board tag categories. */
enum class TagCategory(val type: Int, val label: String) {
    GENERAL(0, "general"),
    ARTIST(1, "artist"),
    COPYRIGHT(3, "copyright"),
    CHARACTER(4, "character"),
    METADATA(5, "metadata"),
    UNKNOWN(-1, "unknown");

    companion object {
        fun fromType(type: Int): TagCategory = entries.firstOrNull { it.type == type } ?: UNKNOWN
    }
}

/**
 * One tag/category entry from a board tag autocomplete endpoint.
 *
 * [type] follows the shared convention on Gelbooru/Danbooru/moebooru:
 * 0=general, 1=artist, 3=copyright, 4=character, 5=metadata.
 */
data class Tag(
    val id: Long,
    val name: String,
    val count: Int,
    val type: Int,
) {
    val category: TagCategory
        get() = TagCategory.fromType(type)
}