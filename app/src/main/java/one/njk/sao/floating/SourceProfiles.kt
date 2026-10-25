package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import one.njk.sao.database.SettingsDataStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Host preferences are independent of extension-owned profiles and the main screen's selection. */
internal data class SourceProfilesSnapshot(val catalog: SourceCatalog = SourceCatalog(),
    val floatingDisabled: Set<String> = emptySet(), val available: Set<String>? = null,
    val homeCategories: Map<String,List<SourceCategory>> = emptyMap(),
    val floatingSelectedId: String? = catalog.selectedId, val nsfwAllowed: Boolean = true) {
    val browsingCatalog get() = catalog.forBrowsing(nsfwAllowed, available)
    val floatingCatalog get() = browsingCatalog.forFloating(floatingDisabled, available, floatingSelectedId)
}

/** Eligibility is derived in memory; blocked profiles and their saved choices remain intact. */
internal fun SourceCatalog.forBrowsing(nsfwAllowed: Boolean, available: Set<String>? = null): SourceCatalog {
    val eligible = profiles.filter { !it.nsfw || nsfwAllowed }.filter { it.id == ALL_SOURCE_ID || available == null || it.id in available }
    val ids = eligible.map { it.id }.toSet()
    val rows = eligible.mapNotNull { profile ->
        val config = profile.configuration
        if (config is AllSourceConfiguration) config.members.filter { it.id in ids && (!it.nsfw || nsfwAllowed) }
            .takeIf { it.isNotEmpty() }?.let { profile.copy(configuration = AllSourceConfiguration(it)) }
        else profile
    }
    return SourceCatalog(rows, selectedId?.let { chosen -> rows.firstOrNull { it.id == chosen }?.id ?: rows.firstOrNull()?.id })
}

internal fun SourceCatalog.forFloating(disabled: Set<String>, available: Set<String>? = null,
    preferredId: String? = selectedId): SourceCatalog {
    val rows = profiles.mapNotNull { profile ->
        if (profile.id in disabled) null else when (val config = profile.configuration) {
            is AllSourceConfiguration -> config.members.filter { it.id !in disabled && (available == null || it.id in available) }
                .takeIf { it.isNotEmpty() }?.let { profile.copy(configuration = AllSourceConfiguration(it)) }
            else -> profile.takeIf { available == null || it.id in available }
        }
    }
    return SourceCatalog(rows, preferredId?.let { selected -> rows.firstOrNull { it.id == selected }?.id ?: rows.firstOrNull()?.id })
}

/** Encrypted profile repository. Only actual configuration changes write to storage. */
@Singleton
class SourceProfiles @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers,
    private val codecs: Map<String, @JvmSuppressWildcards SourceConfigurationCodec>,
    private val mediaCache: SourceMediaCache? = null) {
    private val mutex = Mutex()
    private var loaded = false
    private val value = MutableStateFlow(SourceProfilesSnapshot())
    private val file = EncryptedSettingsFile(context, "source-profiles-v1", "sao-source-profiles-v1")
    private val legacy = EncryptedSettingsFile(context, "immich-connection-v1", "sao-immich-connection-v1")
    private val settings = SettingsDataStore(context)
    internal val snapshots = combine(flow { current(); emitAll(value) }, settings.currentModeFlow()) { snapshot, allowed ->
        snapshot.copy(nsfwAllowed = allowed)
    }
    val catalogs = snapshots.map { it.catalog }.distinctUntilChanged()
    val browsingCatalogs = snapshots.map { it.browsingCatalog }.distinctUntilChanged()
    val floatingCatalogs = snapshots.map { it.floatingCatalog }.distinctUntilChanged()

    suspend fun current(): SourceCatalog = withContext(dispatchers.io) { mutex.withLock { load().catalog } }
    suspend fun currentBrowsing(): SourceCatalog = snapshots.first().browsingCatalog
    suspend fun currentFloating(): SourceCatalog = snapshots.first().floatingCatalog

    suspend fun upsert(profile: SourceProfile, select: Boolean = false) = updateSnapshot { snapshot ->
        val catalog = snapshot.catalog
        val existing = catalog.profiles.indexOfFirst { it.id == profile.id }
        val profiles = catalog.profiles.toMutableList().apply {
            if (existing < 0) add(profile) else set(existing, profile)
        }
        snapshot.copy(catalog = SourceCatalog(profiles, if (select) profile.id else catalog.selectedId ?: profile.id),
            floatingSelectedId = snapshot.floatingSelectedId ?: profile.id)
    }
    suspend fun select(id: String?) {
        val allowed = settings.currentModeFlow().first()
        update { catalog -> if (catalog.profiles.any { it.id == id && it.nsfw && !allowed }) catalog else catalog.copy(selectedId = id) }
    }
    suspend fun selectFloating(id: String?) {
        val allowed = settings.currentModeFlow().first()
        updateSnapshot {
            if (it.catalog.profiles.any { profile -> profile.id == id && profile.nsfw && !allowed }) return@updateSnapshot it
            require(id == null || it.catalog.profiles.any { profile -> profile.id == id })
            it.copy(floatingSelectedId = id)
        }
    }
    suspend fun remove(id: String) = update {
        val remaining = it.profiles.filterNot { profile -> profile.id == id }
        SourceCatalog(remaining, it.selectedId?.takeUnless { selected -> selected == id } ?: remaining.firstOrNull()?.id)
    }
    suspend fun enableAll(enabled: Boolean) = update { it.withAllSource(enabled, value.value.available) }
    suspend fun enableInFloating(id: String, enabled: Boolean) {
        val allowed = settings.currentModeFlow().first()
        updateSnapshot {
            if (enabled && it.catalog.profiles.any { profile -> profile.id == id && profile.nsfw && !allowed }) return@updateSnapshot it
            require(id != ALL_SOURCE_ID && it.catalog.profiles.any { profile -> profile.id == id })
            it.copy(floatingDisabled = if (enabled) it.floatingDisabled - id else it.floatingDisabled + id,
                floatingSelectedId = it.floatingSelectedId ?: id.takeIf { enabled })
        }
    }
    suspend fun homeCategories(id: String, categories: List<SourceCategory>?) = updateSnapshot {
        require(id==ALL_SOURCE_ID || it.catalog.profiles.any { profile -> profile.id==id })
        if(categories!=null) {
            require(categories.size<=40 && categories.map { row -> row.id }.distinct().size==categories.size)
            require(categories.all { row -> row.id.length in 1..80 && row.label.isNotBlank() && row.label.length<=80 && row.query.text.length<=1024 })
        }
        it.copy(homeCategories=if(categories==null) it.homeCategories-id else it.homeCategories+(id to categories.toList()))
    }
    /** Installation changes are process state, not preference writes. */
    suspend fun available(ids: Set<String>) = withContext(dispatchers.io) { mutex.withLock {
        val old = load()
        value.value = old.copy(catalog = old.catalog.withAllSource(available = ids), available = ids)
    } }
    private suspend fun update(transform: (SourceCatalog) -> SourceCatalog) = updateSnapshot { it.copy(catalog = transform(it.catalog)) }
    private suspend fun updateSnapshot(transform: (SourceProfilesSnapshot) -> SourceProfilesSnapshot) = withContext(dispatchers.io) { mutex.withLock {
        val old = load(); val proposed = transform(old)
        val next = proposed.copy(catalog = proposed.catalog.withAllSource(available = proposed.available),
            floatingDisabled = proposed.floatingDisabled.intersect(proposed.catalog.profiles.map { it.id }.toSet()),
            floatingSelectedId = proposed.floatingSelectedId?.let { id ->
                id.takeIf { selected -> proposed.catalog.profiles.any { it.id == selected } }
                    ?: proposed.catalog.forFloating(proposed.floatingDisabled, proposed.available, id).selectedId
            },
            homeCategories=proposed.homeCategories.filterKeys { id -> id==ALL_SOURCE_ID || proposed.catalog.profiles.any { it.id==id } })
        if (old != next) {
            write(next); value.value = next
            old.catalog.profiles.filter { before -> next.catalog.profiles.none { it.identity == before.identity } }
                .forEach { mediaCache?.remove(it.identity) }
        }
    } }

    private fun load(): SourceProfilesSnapshot {
        if (!loaded) {
            val json = file.read()
            value.value = if (json != null) decode(json) else {
                val old = legacy.read()
                if (old == null) SourceProfilesSnapshot() else {
                    val profile = SourceProfile(UUID.randomUUID().toString(), one.njk.sao.AppLanguages.context(context).getString(one.njk.sao.R.string.source_imported_name),
                        UnknownSourceConfiguration("immich",old.toString()))
                    SourceProfilesSnapshot(SourceCatalog(listOf(profile), profile.id.takeIf { old.optBoolean("enabled", true) })).also(::write)
                }
            }
            // Migration is committed before removing the old file; a failed write keeps it intact.
            legacy.delete(); loaded = true
        }
        return value.value
    }
    private fun decode(json: JSONObject): SourceProfilesSnapshot {
        require(json.getInt("version") == 1)
        val rows = json.getJSONArray("profiles")
        val profiles = List(rows.length()) { index ->
            val row = rows.getJSONObject(index); val kind = row.getString("kind")
            val configuration = row.getJSONObject("configuration")
            SourceProfile(row.getString("id"), row.getString("name"), codecs[kind]?.decode(configuration)
                ?: UnknownSourceConfiguration(kind, configuration.toString()), row.optBoolean("nsfw"))
        }
        val selected = json.optString("selected")
        val catalog = SourceCatalog(profiles, selected.takeIf { id -> profiles.any { it.id == id } })
            .withAllSource(json.optBoolean("allEnabled"))
        val resolved = if(selected == ALL_SOURCE_ID && catalog.profiles.any { it.id == ALL_SOURCE_ID }) catalog.copy(selectedId = selected) else catalog
        val disabled = SourceWire.strings(json.optJSONArray("floatingDisabled")).toSet().intersect(profiles.map { it.id }.toSet())
        val savedCategories=json.optJSONObject("homeCategories")
        val categories=savedCategories?.keys()?.asSequence()?.filter { id -> id==ALL_SOURCE_ID || profiles.any { it.id==id } }?.associateWith { id ->
            SourceWire.capabilities(JSONObject().put("categories",savedCategories.getJSONArray(id))).categories
        }.orEmpty()
        // Existing installs keep their current choice once; subsequent changes are independent.
        val floatingSelected = if (json.has("floatingSelected")) json.optString("floatingSelected").takeIf { id -> resolved.profiles.any { it.id == id } }
            else resolved.selectedId
        return SourceProfilesSnapshot(resolved,disabled,homeCategories=categories,floatingSelectedId=floatingSelected)
    }
    private fun write(snapshot: SourceProfilesSnapshot) {
        val catalog = snapshot.catalog
        val rows = JSONArray()
        catalog.profiles.filterNot { it.id == ALL_SOURCE_ID }.forEach { profile ->
            val config = profile.configuration
            val json = if (config is UnknownSourceConfiguration) JSONObject(config.json)
                else checkNotNull(codecs[config.kind]).encode(config)
            rows.put(JSONObject().put("id", profile.id).put("name", profile.name)
                .put("kind", config.kind).put("configuration", json).put("nsfw", profile.nsfw))
        }
        file.write(JSONObject().put("version", 1).put("profiles", rows).put("selected", catalog.selectedId.orEmpty())
            .put("floatingSelected",snapshot.floatingSelectedId.orEmpty())
            .put("allEnabled",catalog.profiles.any { it.id == ALL_SOURCE_ID }).put("floatingDisabled",JSONArray(snapshot.floatingDisabled.sorted()))
            .put("homeCategories",JSONObject().apply { snapshot.homeCategories.forEach { (id,categories) ->
                put(id,SourceWire.capabilities(SourceCapabilities(categories=categories)).getJSONArray("categories"))
            } }))
    }
}

/** AES-GCM + AtomicFile, excluded from backup by its noBackupFilesDir location. */
internal class EncryptedSettingsFile(context: Context, name: String, private val alias: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))
    fun read(): JSONObject? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size > 12 && bytes.size <= 16 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    fun write(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        require(bytes.size <= 16 * 1024 * 1024)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    fun delete() = file.delete()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
