package one.njk.sao.floating


/** Provider adapters translate setup failures without exposing their client types to picker UI. */
class SourceReadException(@androidx.annotation.StringRes val messageRes: Int, cause: Exception) :
    java.io.IOException(cause)
