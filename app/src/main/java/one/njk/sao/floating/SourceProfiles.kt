package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

/** Encrypted profile repository. Only actual configuration changes write to storage. */
@Singleton
class SourceProfiles @Inject constructor(@ApplicationContext context: Context,
    private val dispatchers: FloatingDispatchers,
    private val codecs: Map<String, @JvmSuppressWildcards SourceConfigurationCodec>,
    private val mediaCache: SourceMediaCache? = null) {
    private val mutex = Mutex()
    private var loaded = false
    private var available: Set<String>? = null
    private val value = MutableStateFlow(SourceCatalog())
    private val file = EncryptedSettingsFile(context, "source-profiles-v1", "sao-source-profiles-v1")
    private val legacy = EncryptedSettingsFile(context, "immich-connection-v1", "sao-immich-connection-v1")
    val catalogs = flow { current(); emitAll(value) }

    suspend fun current(): SourceCatalog = withContext(dispatchers.io) { mutex.withLock { load() } }

    suspend fun upsert(profile: SourceProfile, select: Boolean = false) = update { catalog ->
        val existing = catalog.profiles.indexOfFirst { it.id == profile.id }
        val profiles = catalog.profiles.toMutableList().apply {
            if (existing < 0) add(profile) else set(existing, profile)
        }
        SourceCatalog(profiles, if (select) profile.id else catalog.selectedId ?: profile.id)
    }
    suspend fun select(id: String?) = update { it.copy(selectedId = id) }
    suspend fun remove(id: String) = update {
        val remaining = it.profiles.filterNot { profile -> profile.id == id }
        SourceCatalog(remaining, it.selectedId?.takeUnless { selected -> selected == id } ?: remaining.firstOrNull()?.id)
    }
    suspend fun enableAll(enabled: Boolean) = update { it.withAllSource(enabled, available) }
    /** Installation changes are process state, not preference writes. */
    suspend fun available(ids: Set<String>) = withContext(dispatchers.io) { mutex.withLock {
        val old = load()
        available = ids
        value.value = old.withAllSource(available = ids)
    } }
    private suspend fun update(transform: (SourceCatalog) -> SourceCatalog) = withContext(dispatchers.io) { mutex.withLock {
        val old = load(); val next = transform(old)
        if (old != next) {
            val resolved = next.withAllSource(available = available)
            write(resolved); value.value = resolved
            old.profiles.filter { before -> next.profiles.none { it.identity == before.identity } }
                .forEach { mediaCache?.remove(it.identity) }
        }
    } }

    private fun load(): SourceCatalog {
        if (!loaded) {
            val json = file.read()
            value.value = if (json != null) decode(json) else {
                val old = legacy.read()
                if (old == null) SourceCatalog() else {
                    val profile = SourceProfile(UUID.randomUUID().toString(), "Imported source",
                        UnknownSourceConfiguration("immich",old.toString()))
                    SourceCatalog(listOf(profile), profile.id.takeIf { old.optBoolean("enabled", true) }).also(::write)
                }
            }
            // Migration is committed before removing the old file; a failed write keeps it intact.
            legacy.delete(); loaded = true
        }
        return value.value
    }
    private fun decode(json: JSONObject): SourceCatalog {
        require(json.getInt("version") == 1)
        val rows = json.getJSONArray("profiles")
        val profiles = List(rows.length()) { index ->
            val row = rows.getJSONObject(index); val kind = row.getString("kind")
            val configuration = row.getJSONObject("configuration")
            SourceProfile(row.getString("id"), row.getString("name"), codecs[kind]?.decode(configuration)
                ?: UnknownSourceConfiguration(kind, configuration.toString()))
        }
        val selected = json.optString("selected")
        val catalog = SourceCatalog(profiles, selected.takeIf { id -> profiles.any { it.id == id } })
            .withAllSource(json.optBoolean("allEnabled"), available)
        return if(selected == ALL_SOURCE_ID && catalog.profiles.any { it.id == ALL_SOURCE_ID }) catalog.copy(selectedId = selected) else catalog
    }
    private fun write(catalog: SourceCatalog) {
        val rows = JSONArray()
        catalog.profiles.filterNot { it.id == ALL_SOURCE_ID }.forEach { profile ->
            val config = profile.configuration
            val json = if (config is UnknownSourceConfiguration) JSONObject(config.json)
                else checkNotNull(codecs[config.kind]).encode(config)
            rows.put(JSONObject().put("id", profile.id).put("name", profile.name)
                .put("kind", config.kind).put("configuration", json))
        }
        file.write(JSONObject().put("version", 1).put("profiles", rows).put("selected", catalog.selectedId.orEmpty()).put("allEnabled",catalog.profiles.any { it.id == ALL_SOURCE_ID }))
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
