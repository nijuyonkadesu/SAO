package one.njk.sao.floating

import one.njk.sao.source.ExtensionFailure
import one.njk.sao.source.SourceException

/** Provider adapters translate setup failures without exposing their client types to picker UI. */
class SourceReadException(@androidx.annotation.StringRes val messageRes: Int, cause: Exception) :
    SourceException(code = (cause as? ExtensionFailure)?.code,
        message = cause.message ?: "Source read failed", cause = cause)
