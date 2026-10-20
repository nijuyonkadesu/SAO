package one.njk.sao.importing

import android.content.Intent
import one.njk.sao.floating.ExtensionConfig
import one.njk.sao.floating.SourceExtensionAccess
import one.njk.sao.source.*
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONArray
import org.json.JSONObject

/** A signed extension can read/control only its own named profile. SQLite state, never backend credentials. */
class ImportSettingsService: ExtensionService() {
    override fun descriptor()=JSONObject().put("name","SAO import settings")
    override suspend fun execute(profile: String,operation: String,payload: JSONObject,caller: String): JSONObject {
        val repository=imports()
        val selected=repository.settingsSource(caller,profile,payload.getString("revision"))
        when(operation) {
            ImportSettings.STATE -> Unit
            ImportSettings.PAUSE -> repository.pause(selected.id)
            ImportSettings.RETRY -> repository.retry(selected.id)
            ImportSettings.CLEAR -> repository.clear(selected.id)
            ImportSettings.CHECK -> repository.check(selected.id)
            ImportSettings.DISCONNECT -> repository.remove(repository.store.routes(selected.id).firstOrNull { it.id==payload.optString("route") } ?: throw ExtensionFailure("TARGET_MISSING"))
            else -> throw ExtensionFailure("UNSUPPORTED")
        }
        val state=repository.store.stats(selected.id)
        val changes=ImportSettings.changes(packageName)
        grantUriPermission(caller,changes,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val routes=repository.store.routes(selected.id)
        if(routes.size>512) throw ExtensionFailure("LIMIT_EXCEEDED")
        return JSONObject().put("source",selected.id).put("changes",changes.toString())
            .put("done",state.done).put("pending",state.pending).put("blocked",state.blocked).put("paused",state.paused).put("error",state.error)
            .put("routes",JSONArray(routes.map { route -> JSONObject().put("id",route.id).put("name",route.name)
                .put("target",route.label).put("checked",route.checked).put("error",route.error) }))
    }
}

/** Shared scope guard for both reverse IPC and picker activities; host/source IDs are never accepted on trust. */
internal suspend fun MediaImports.settingsSource(caller: String,profile: String,revision: String): SourceProfile {
    val app=application
    val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
    val selected=access.sources().current().profiles.firstOrNull { value ->
        (value.configuration as? ExtensionConfig)?.let { it.packageName==caller && it.profile==profile }==true
    } ?: throw ExtensionFailure("PROFILE_MISSING")
    val config=selected.configuration as ExtensionConfig
    if(config.revision!=revision) throw ExtensionFailure("PROFILE_CHANGED")
    access.extensions().requireInstalled(config)
    return selected
}
