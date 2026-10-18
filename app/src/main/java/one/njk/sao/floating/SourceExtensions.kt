package one.njk.sao.floating

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.njk.sao.source.*
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Only public profile metadata is retained by SAO. Credentials remain in the provider APK. */
data class ExtensionConfig(val packageName: String, val service: String, val profile: String,
    val revision: String, val providerName: String, val summary: String = "") : SourceConfiguration {
    override val kind = "extension"
    override val identity get() = digest("$packageName|$service|$profile|$revision")
    val component get() = ComponentName(packageName,service)
}
class ExtensionConfigurationCodec @Inject constructor() : SourceConfigurationCodec {
    override fun encode(configuration: SourceConfiguration) = (configuration as ExtensionConfig).let {
        JSONObject().put("package",it.packageName).put("service",it.service).put("profile",it.profile)
            .put("revision",it.revision).put("provider",it.providerName).put("summary",it.summary)
    }
    override fun decode(json: JSONObject) = ExtensionConfig(json.getString("package"),json.getString("service"),
        json.getString("profile"),json.getString("revision"),json.getString("provider"),json.optString("summary"))
}

data class InstalledSourceExtension(val component: ComponentName, val name: String, val settings: String,
    val legacyKinds: Set<String>) {
    fun profile(value: ExtensionProfile): SourceProfile {
        val id = java.util.UUID.nameUUIDFromBytes("${component.flattenToString()}:${value.id}".toByteArray()).toString()
        return SourceProfile(id,value.name,ExtensionConfig(component.packageName,component.className,value.id,value.revision,name,value.summary))
    }
}

@Singleton
class SourceExtensions @Inject constructor(@ApplicationContext private val context: Context,
    private val profiles: SourceProfiles) {
    suspend fun installed(): List<InstalledSourceExtension> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        manager.queryIntentServices(android.content.Intent(ExtensionProtocol.ACTION),PackageManager.GET_META_DATA).mapNotNull { result ->
            val service = result.serviceInfo ?: return@mapNotNull null
            val data = service.metaData ?: return@mapNotNull null
            if (!service.enabled || !service.exported || data.getInt(ExtensionProtocol.API_VERSION) != ExtensionProtocol.VERSION ||
                manager.checkSignatures(context.packageName,service.packageName) != PackageManager.SIGNATURE_MATCH) return@mapNotNull null
            val settings = data.getString(ExtensionProtocol.SETTINGS) ?: return@mapNotNull null
            InstalledSourceExtension(ComponentName(service.packageName,service.name),service.loadLabel(manager).toString(),settings,
                data.getString(ExtensionProtocol.LEGACY_KINDS).orEmpty().split(',').filter(String::isNotBlank).toSet())
        }
    }
    suspend fun requireInstalled(config: ExtensionConfig): InstalledSourceExtension = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val service = try { manager.getServiceInfo(config.component,PackageManager.GET_META_DATA) }
            catch (_: PackageManager.NameNotFoundException) { throw ExtensionFailure("UNAVAILABLE") }
        if(manager.checkSignatures(context.packageName,config.packageName) != PackageManager.SIGNATURE_MATCH) throw ExtensionFailure("UNTRUSTED")
        if(service.metaData?.getInt(ExtensionProtocol.API_VERSION) != ExtensionProtocol.VERSION) throw ExtensionFailure("INCOMPATIBLE")
        installed().firstOrNull { it.component == config.component } ?: throw ExtensionFailure("UNAVAILABLE")
    }
    suspend fun refresh(extension: InstalledSourceExtension, selectedProfile: String? = null) {
        val rows = ReadRetry.run { ExtensionClient(context,extension.component).profiles() }.map(extension::profile)
        val old = profiles.current()
        val linked = old.profiles.filter { (it.configuration as? ExtensionConfig)?.component == extension.component }
        linked.filter { existing -> rows.none { it.id == existing.id } }.forEach { profiles.remove(it.id) }
        rows.forEach { profiles.upsert(it) }
        selectedProfile?.let { id -> rows.firstOrNull { (it.configuration as ExtensionConfig).profile == id }?.let { profiles.select(it.id) } }
    }
    /** One-time opaque legacy handoff, only to an installed extension signed by this publisher. */
    suspend fun migrate(extension: InstalledSourceExtension) {
        val old = profiles.current()
        for (profile in old.profiles) {
            val config = profile.configuration as? UnknownSourceConfiguration ?: continue
            if (config.kind !in extension.legacyKinds) continue
            val reply = ExtensionClient(context,extension.component).request(profile.id,ExtensionProtocol.IMPORT,
                JSONObject().put("name",profile.name).put("configuration",JSONObject(config.json)))
            val replacement = extension.profile(SourceWire.profile(reply))
            profiles.upsert(replacement,select = old.selectedId == profile.id)
            profiles.remove(profile.id)
        }
    }
}

class ExtensionSourceFactory @Inject constructor(@ApplicationContext private val context: Context,
    private val extensions: SourceExtensions, private val cache: SourceMediaCache) : MediaSourceFactory {
    override fun create(profile: SourceProfile) = ExtensionMediaSource(context,profile,extensions,cache)
}

internal fun ExtensionFailure.messageResource(): Int = when(code) {
    "UNAVAILABLE", "DISCONNECTED" -> one.njk.sao.R.string.source_extension_missing
    "INCOMPATIBLE" -> one.njk.sao.R.string.source_extension_update
    "INVALID_RESPONSE" -> one.njk.sao.R.string.source_invalid_response
    "PROFILE_MISSING", "PROFILE_CHANGED" -> one.njk.sao.R.string.source_profile_changed
    "HTTP_401", "HTTP_403" -> one.njk.sao.R.string.source_extension_auth
    "HTTP_429" -> one.njk.sao.R.string.source_extension_rate_limit
    "TLS" -> one.njk.sao.R.string.source_extension_tls
    "UNTRUSTED" -> one.njk.sao.R.string.source_extension_untrusted
    "NETWORK", "TIMEOUT" -> one.njk.sao.R.string.source_extension_network
    "MEDIA_TOO_LARGE" -> one.njk.sao.R.string.source_media_too_large
    "HTTP_404" -> one.njk.sao.R.string.source_media_missing
    "PERMISSION_REQUIRED" -> one.njk.sao.R.string.source_folder_permission
    "MEDIA_CHANGED" -> one.njk.sao.R.string.source_media_changed
    else -> one.njk.sao.R.string.source_extension_failed
}
