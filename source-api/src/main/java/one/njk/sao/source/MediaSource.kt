package one.njk.sao.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException

interface MediaSource {
    val changes: Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()

    val capabilities: Flow<SourceCapabilities> get() = flowOf(SourceCapabilities())

    val capabilitiesState: Flow<SourceCapabilitiesState>
        get() = capabilities.map { SourceCapabilitiesState.Ready(it) }

    val identity: Flow<String>

    /** Bind queued helpers to their initiating source. The scoped source must not escape [block]. */
    suspend fun <T> withSource(expectedIdentity: String, block: suspend (MediaSource) -> T): T {
        if (identity.first() != expectedIdentity) throw CancellationException("Source changed")
        val result = block(this)
        if (identity.first() != expectedIdentity) throw CancellationException("Source changed")
        return result
    }

    suspend fun owns(item: MediaItem): Boolean

    suspend fun page(query: MediaQuery, cursor: String? = null): MediaPage

    suspend fun filters(): List<String> = throw SourceUnsupportedException(SourceFeature.FILTERS)

    /** Keep preview resources alive until decoding/copying finishes; the preview must not escape. */
    suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview) -> T): T

    suspend fun warmPreview(item: MediaItem) { withPreview(item) { } }

    suspend fun <T> withPersonPreview(person: SearchPerson, block: suspend (Preview?) -> T): T =
        throw SourceUnsupportedException(SourceFeature.PERSON_PREVIEW)

    suspend fun openSequence(anchor: MediaItem): MediaSequence = throw SourceUnsupportedException(SourceFeature.SEQUENCE)

    suspend fun suggestions(query: MediaQuery, cursor: Int): SearchSuggestions = throw SourceUnsupportedException(SourceFeature.SUGGESTIONS)

    suspend fun people(name: String, page: Int = 1): PeoplePage = throw SourceUnsupportedException(SourceFeature.PEOPLE)

    suspend fun places(scope: PlaceScope): List<String> = throw SourceUnsupportedException(SourceFeature.PLACES)

    suspend fun <T> withRendition(item: MediaItem, kind: Rendition, block: suspend (MediaHandle) -> T): T

    /** The address and any grants must not escape this lease. Fullscreen can request the original. */
    suspend fun <T> withPlayback(item: MediaItem, original: Boolean = false, block: suspend (MediaPlayback) -> T): T =
        withRendition(item, if (original || !item.isVideo) Rendition.ORIGINAL else Rendition.PLAYBACK) {
            block(MediaPlayback(android.net.Uri.fromFile(it.requireFile())))
        }
}
