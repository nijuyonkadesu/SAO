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
    val revision: String, val providerName: String, val summary: String = "", val signer: String = "") : SourceConfiguration {
    override val kind = "extension"
    override val identity get() = digest("$packageName|$service|$profile|$revision" + signer.takeIf(String::isNotEmpty)?.let { "|signer:$it" }.orEmpty())
    val component get() = ComponentName(packageName,service)
}
class ExtensionConfigurationCodec @Inject constructor() : SourceConfigurationCodec {
    override fun encode(configuration: SourceConfiguration) = (configuration as ExtensionConfig).let {
        JSONObject().put("package",it.packageName).put("service",it.service).put("profile",it.profile)
            .put("revision",it.revision).put("provider",it.providerName).put("summary",it.summary).put("signer",it.signer)
    }
    override fun decode(json: JSONObject) = ExtensionConfig(json.getString("package"),json.getString("service"),
        json.getString("profile"),json.getString("revision"),json.getString("provider"),json.optString("summary"),json.optString("signer"))
}

data class InstalledSourceExtension(val component: ComponentName, val name: String, val settings: String,
    val legacyKinds: Set<String>, val approval: ExtensionApproval = ExtensionApproval.PUBLISHER, val signer: String = "") {
    fun profile(value: ExtensionProfile): SourceProfile {
        val id = java.util.UUID.nameUUIDFromBytes("${component.flattenToString()}:${value.id}".toByteArray()).toString()
        return SourceProfile(id,value.name,ExtensionConfig(component.packageName,component.className,value.id,value.revision,name,value.summary,signer.takeUnless { approval==ExtensionApproval.PUBLISHER }.orEmpty()),value.nsfw)
    }
}

/** Package inventory is separate from providers that pass the binding trust/protocol checks. */
data class InstalledExtensionApp(val packageName: String, val name: String, val version: String,
    val approval: ExtensionApproval = ExtensionApproval.PUBLISHER, val signer: String = "", val compatible: Boolean = true,
    val versionCode: Long = 0, val icon: android.graphics.drawable.Drawable? = null)

@Singleton
class SourceExtensions @Inject constructor(@ApplicationContext private val context: Context,
    private val profiles: SourceProfiles,
    private val trust: ExtensionTrust = ExtensionTrust(context,one.njk.sao.database.SettingsDataStore(context))) {
    suspend fun installedApps(): List<InstalledExtensionApp> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val compatiblePackages=candidates().map { it.component.packageName }.toSet()
        manager.queryIntentServices(android.content.Intent(ExtensionProtocol.ACTION),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS)
            .mapNotNull { it.serviceInfo?.applicationInfo }.distinctBy { it.packageName }.mapNotNull { app ->
                try {
                    val info = manager.getPackageInfo(app.packageName, 0)
                    val signer=SourceSignatures.identity(context,app.packageName)
                    val compatible=app.packageName in compatiblePackages
                    InstalledExtensionApp(app.packageName, manager.getApplicationLabel(app).toString(), info.versionName.orEmpty(),trust.status(app.packageName,signer),signer,compatible,info.longVersionCode,manager.getApplicationIcon(app))
                } catch (_: PackageManager.NameNotFoundException) { null }
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    suspend fun installed(): List<InstalledSourceExtension> = candidates().filter { it.approval!=ExtensionApproval.UNTRUSTED }
    /** Metadata-only discovery never binds an unapproved APK or reads its profiles. */
    suspend fun candidates(): List<InstalledSourceExtension> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        manager.queryIntentServices(android.content.Intent(ExtensionProtocol.ACTION),PackageManager.GET_META_DATA).mapNotNull { result ->
            val service = result.serviceInfo ?: return@mapNotNull null
            val data = service.metaData ?: return@mapNotNull null
            if (!service.enabled || !service.exported || data.getInt(ExtensionProtocol.API_VERSION) != ExtensionProtocol.VERSION) return@mapNotNull null
            val settings = data.getString(ExtensionProtocol.SETTINGS) ?: return@mapNotNull null
            val signer=SourceSignatures.identity(context,service.packageName)
            InstalledSourceExtension(ComponentName(service.packageName,service.name),service.loadLabel(manager).toString(),settings,
                data.getString(ExtensionProtocol.LEGACY_KINDS).orEmpty().split(',').filter(String::isNotBlank).toSet(),trust.status(service.packageName,signer),signer)
        }
    }
    suspend fun installedRays(): Set<String> = withContext(Dispatchers.IO) {
        one.njk.sao.rays.RaysClient.packages.filter { one.njk.sao.rays.RaysClient.installed(context,it) }.toSet()
    }
    suspend fun requireInstalled(config: ExtensionConfig): InstalledSourceExtension = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val service = try { manager.getServiceInfo(config.component,PackageManager.GET_META_DATA) }
            catch (_: PackageManager.NameNotFoundException) { throw ExtensionFailure("UNAVAILABLE") }
        if(!trust.allowed(config.packageName)) throw ExtensionFailure("UNTRUSTED")
        if(service.metaData?.getInt(ExtensionProtocol.API_VERSION) != ExtensionProtocol.VERSION) throw ExtensionFailure("INCOMPATIBLE")
        val installed=installed().firstOrNull { it.component == config.component } ?: throw ExtensionFailure("UNAVAILABLE")
        val expected=installed.signer.takeUnless { installed.approval==ExtensionApproval.PUBLISHER }.orEmpty()
        if(config.signer!=expected) throw ExtensionFailure("PROFILE_CHANGED")
        installed
    }
    suspend fun refresh(extension: InstalledSourceExtension, selectedProfile: String? = null, floating: Boolean = false) {
        val current=candidates().firstOrNull { it.component==extension.component } ?: throw ExtensionFailure("UNAVAILABLE")
        if(current.approval==ExtensionApproval.UNTRUSTED) throw ExtensionFailure("UNTRUSTED")
        val rows = ReadRetry.run { ExtensionClient(context,current.component) { one.njk.sao.AppLanguages.tags }.profiles() }.map(current::profile)
        val old = profiles.current()
        val linked = old.profiles.filter { (it.configuration as? ExtensionConfig)?.component == extension.component }
        linked.filter { existing -> rows.none { it.id == existing.id } }.forEach { profiles.remove(it.id) }
        rows.forEach { profiles.upsert(it) }
        selectedProfile?.let { id -> rows.firstOrNull { (it.configuration as ExtensionConfig).profile == id }?.let { if (floating) profiles.selectFloating(it.id) else profiles.select(it.id) } }
    }
    /** One-time opaque legacy handoff, only to an installed extension signed by this publisher. */
    suspend fun migrate(extension: InstalledSourceExtension) {
        if(!SourceSignatures.samePublisher(context,extension.component.packageName)) return
        val old = profiles.current()
        for (profile in old.profiles) {
            val config = profile.configuration as? UnknownSourceConfiguration ?: continue
            if (config.kind !in extension.legacyKinds) continue
            val reply = ExtensionClient(context,extension.component) { one.njk.sao.AppLanguages.tags }.request(profile.id,ExtensionProtocol.IMPORT,
                JSONObject().put("name",profile.name).put("configuration",JSONObject(config.json)))
            val replacement = extension.profile(SourceWire.profile(reply))
            val floatingSelected = profiles.currentFloating().selectedId == profile.id
            profiles.upsert(replacement,select = old.selectedId == profile.id)
            if (floatingSelected) profiles.selectFloating(replacement.id)
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
