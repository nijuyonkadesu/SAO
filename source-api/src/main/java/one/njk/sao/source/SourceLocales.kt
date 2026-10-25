package one.njk.sao.source

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import org.json.JSONObject
import java.util.Locale

/** Presentation only. Locale never participates in profile identity, queries or media cache keys. */
object SourceLocales {
    const val EXTRA_LANGUAGE_TAGS = "one.njk.sao.source.LANGUAGE_TAGS"
    const val REQUEST_LANGUAGE_TAGS = "_languageTags"

    fun languageTags(context: Context): String = context.resources.configuration.locales.toLanguageTags()

    /** Bound untrusted IPC/Intent input before handing it to Android's locale parser. */
    fun validated(tags: String?): String? {
        if (tags.isNullOrBlank() || tags.length > 256) return null
        val parts = tags.split(',')
        if (parts.size > 8) return null
        return try {
            parts.map { Locale.Builder().setLanguageTag(it).build() }
                .takeIf { locales -> locales.all { it.language.isNotBlank() && it.toLanguageTag() != "und" } }
                ?.distinct()?.joinToString(",") { it.toLanguageTag() }
        } catch (_: java.util.IllformedLocaleException) { null }
    }

    fun configuration(base: Configuration, tags: String?): Configuration = Configuration(base).apply {
        validated(tags)?.let { setLocales(LocaleList.forLanguageTags(it)) }
    }

    fun context(base: Context, tags: String?): Context =
        base.createConfigurationContext(configuration(base.resources.configuration, tags))

    fun context(base: Context, request: JSONObject): Context =
        context(base, request.optString(REQUEST_LANGUAGE_TAGS))
}
