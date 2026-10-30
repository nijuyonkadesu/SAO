package one.njk.sao.source

open class SourceException(
    open val code: String? = null,
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

enum class SourceFeature {
    CAPABILITIES,
    PAGE,
    FILTERS,
    PREVIEW,
    RENDITION,
    PERSON_PREVIEW,
    SEQUENCE,
    SUGGESTIONS,
    PEOPLE,
    PLACES,
    SOURCE,
}

class SourceUnsupportedException(val feature: SourceFeature) :
    SourceException(code = "UNSUPPORTED", message = "Unsupported source operation: ${feature.name.lowercase()}")
