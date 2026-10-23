package one.njk.sao.source

enum class SearchFacet { PEOPLE, PLACES, MEDIA, EMOJI_TAGS }

/** An empty successful declaration is different from an unavailable provider. */
sealed interface SourceCapabilitiesState {
    data object Loading : SourceCapabilitiesState
    data class Ready(val value: SourceCapabilities) : SourceCapabilitiesState
    data class Failed(val error: Exception) : SourceCapabilitiesState
}

/** A small provider-authored home collection; the host submits its query without interpreting it. */
data class SourceCategory(val id: String, val label: String, val query: MediaQuery = MediaQuery(), val adult: Boolean = false)

/** The host knows capabilities, never provider kinds. Unknown future capabilities are ignored. */
data class SourceCapabilities(
    val modes: Set<SearchMode> = emptySet(),
    val facets: Set<SearchFacet> = emptySet(),
    val random: Boolean = false,
    val sequence: Boolean = false,
    val defaultMode: SearchMode = SearchMode.CONTEXT,
    /** Host-attached owner; omitted from provider wire records. Null means not loaded yet. */
    val sourceId: String? = null,
    /** Optional read-granted content URI for local index changes; observed only while browsing. */
    val changesUri: String? = null,
    val categories: List<SourceCategory> = emptyList(),
    /** Optional query completion, scoped to the declared modes; absence means no suggestion IPC. */
    val suggestionModes: Set<SearchMode> = emptySet(),
    /** Optional media intake; its live destinations/permissions are discovered explicitly. */
    val imports: Boolean = false,
) {
    val searchOptions get() = modes.isNotEmpty() || facets.isNotEmpty()
    fun normalize(query: MediaQuery): MediaQuery = query.copy(
        mode = query.mode.takeIf { it in modes } ?: defaultMode,
        filter = query.filter.takeIf { SearchFacet.EMOJI_TAGS in facets },
        randomRequest = query.randomRequest.takeIf { random },
        constraints = query.constraints.copy(
            people = query.constraints.people.takeIf { SearchFacet.PEOPLE in facets }.orEmpty(),
            country = query.constraints.country.takeIf { SearchFacet.PLACES in facets },
            state = query.constraints.state.takeIf { SearchFacet.PLACES in facets },
            city = query.constraints.city.takeIf { SearchFacet.PLACES in facets },
            media = query.constraints.media.takeIf { SearchFacet.MEDIA in facets } ?: SearchMedia.ALL,
        ),
    )
}

/** Public whole-source classification; older v1 providers omit nsfw and remain compatible. */
data class ExtensionProfile(val id: String, val name: String, val revision: String, val summary: String = "", val nsfw: Boolean = false)

object ExtensionProtocol {
    const val VERSION = 1
    const val ACTION = "one.njk.sao.source.EXTENSION"
    const val API_VERSION = "one.njk.sao.source.API_VERSION"
    const val SETTINGS = "one.njk.sao.source.SETTINGS"
    const val LEGACY_KINDS = "one.njk.sao.source.LEGACY_KINDS"
    const val PROFILE = "source_profile_id"
    const val MAX_REQUEST = 64 * 1024
    const val MAX_RESPONSE = 256 * 1024
    const val PROFILES = "profiles"
    const val IMPORT = "import"
    const val DELETE_PROFILE = "deleteProfile"
    const val CAPABILITIES = "capabilities"
    const val SEARCH = "search"
    const val SUGGESTIONS = "suggestions"
    const val FILTERS = "filters"
    const val PEOPLE = "people"
    const val PLACES = "places"
    const val MEDIA = "media"
    const val PERSON_MEDIA = "personMedia"
    const val SEQUENCE_OPEN = "sequenceOpen"
    const val SEQUENCE_PAGE = "sequencePage"
    const val SEQUENCE_CLOSE = "sequenceClose"
    const val IMPORT_OPTIONS = "importOptions"
    const val IMPORT_MEDIA = "importMedia"
    const val IMPORT_COMPLETE = "importComplete"
    const val IMPORT_CANCEL = "importCancel"
}
