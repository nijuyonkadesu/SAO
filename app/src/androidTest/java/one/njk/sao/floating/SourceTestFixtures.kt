package one.njk.sao.floating

import android.content.Context
import javax.inject.Provider

/** Existing single-connection contract fixtures use one named profile in the real repository. */
internal class ImmichTestSettings(val profiles: SourceProfiles) {
    suspend fun current(): ImmichConfig? {
        val catalog = profiles.current()
        val profile = catalog.selected ?: catalog.profiles.firstOrNull()
        return (profile?.configuration as? ImmichConfig)
    }
    suspend fun save(config: ImmichConfig?, enabled: Boolean = true) {
        if (config == null) { profiles.current().profiles.forEach { profiles.remove(it.id) }; return }
        val profile = profiles.current().profiles.firstOrNull()
        profiles.upsert(SourceProfile(profile?.id ?: "fee74d83-4019-4b6b-9728-c12dfdfebd89", "Immich", config), enabled)
        if (!enabled) profiles.select(null)
    }
}
internal fun FloatingTestAccess.immichSettings() = ImmichTestSettings(sources())
internal fun SwitchingMediaSource(context: Context, local: LocalMediaSource, settings: ImmichTestSettings,
    dispatchers: FloatingDispatchers) = SwitchingMediaSource(local, settings.profiles,
        mapOf("immich" to Provider<MediaSourceFactory> { ImmichSourceFactory(context, dispatchers) }), dispatchers)
internal suspend fun SourceProfiles.restore(catalog: SourceCatalog) {
    current().profiles.forEach { remove(it.id) }
    catalog.profiles.forEach { upsert(it) }; select(catalog.selectedId)
}
