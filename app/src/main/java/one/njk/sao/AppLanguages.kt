package one.njk.sao

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.source.SourceLocales
import java.util.Locale

/** One language preference, owned by Android/AppCompat. No mirrored preferences in source APKs. */
object AppLanguages {
    private val mutable = MutableStateFlow("")
    private var requested = LocaleListCompat.getEmptyLocaleList()
    val changes = mutable.asStateFlow()
    val tags: String get() = mutable.value

    fun initialize(app: Application) {
        requested = LocaleManagerCompat.getApplicationLocales(app)
        refresh(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Pre-33 AppCompat restores auto-stored locales while attaching the first Activity.
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                requested = AppCompatDelegate.getApplicationLocales(); refresh(app)
            }
            override fun onActivityResumed(activity: Activity) {
                requested = AppCompatDelegate.getApplicationLocales(); refresh(app)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun refresh(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 33) requested = LocaleManagerCompat.getApplicationLocales(context)
        mutable.value = (if (requested.isEmpty) LocaleManagerCompat.getSystemLocales(context) else requested).toLanguageTags()
    }

    fun select(context: Context, tag: String?) {
        requested = LocaleListCompat.forLanguageTags(tag.orEmpty())
        AppCompatDelegate.setApplicationLocales(requested)
        refresh(context)
    }

    fun context(base: Context): Context = SourceLocales.context(base, tags)

    fun choices(context: Context): List<String> = context.resources.getStringArray(R.array.sao_supported_language_tags).toList()

    fun label(tag: String): String = Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
}
