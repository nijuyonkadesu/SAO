package one.njk.sao.floating

/** One temporary, source-owned timeline. Cursors are opaque and valid only for this session. */
interface MediaSequence : AutoCloseable {
    suspend fun initial(): SequencePage
    suspend fun page(direction: SequenceDirection, cursor: String): SequencePage
}

enum class SequenceDirection { NEWER, OLDER }
data class SequencePage(val items: List<MediaItem>, val newer: String? = null, val older: String? = null)
