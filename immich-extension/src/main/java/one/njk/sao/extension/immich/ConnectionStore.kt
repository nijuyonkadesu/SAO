package one.njk.sao.extension.immich

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.ExtensionProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** This APK exclusively owns connections. Public profile records contain no server or API key. */
class ConnectionStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(context.noBackupFilesDir,"connections-v1"))
    data class Connection(val id: String, val name: String, val config: ImmichConfig, val revision: String) {
        fun publicProfile() = ExtensionProfile(id,name,revision,if (config.albumId == null) "All media" else "Selected album")
    }
    suspend fun list(): List<Connection> = withContext(Dispatchers.IO) { lock.withLock { read() } }
    suspend fun get(id: String) = list().firstOrNull { it.id == id } ?: throw one.njk.sao.source.ExtensionFailure("PROFILE_MISSING")
    suspend fun save(id: String, name: String, config: ImmichConfig): Connection = withContext(Dispatchers.IO) { lock.withLock {
        UUID.fromString(id); require(name.isNotBlank()); config.connection
        val existing = read()
        val prior = existing.firstOrNull { it.id == id }
        val value = Connection(id,name.trim(),config,prior?.revision?.takeIf { prior.config.identity == config.identity } ?: UUID.randomUUID().toString())
        write(existing.filterNot { it.id == id } + value)
        if (prior != null && prior.revision != value.revision) revoke(prior)
        value
    } }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { lock.withLock {
        val all = read(); write(all.filterNot { it.id == id }); all.firstOrNull { it.id == id }?.let(::revoke)
    } }
    private fun revoke(connection: Connection) {
        val uri = android.net.Uri.Builder().scheme("content").authority("${app.packageName}.media")
            .appendPath(connection.id).appendPath(connection.revision).build()
        app.revokeUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): List<Connection> {
        if (!file.baseFile.exists()) return emptyList()
        val bytes = file.readFully(); require(bytes.size in 29..16*1024*1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
        val rows = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        return List(rows.length()) { index -> rows.getJSONObject(index).let {
            Connection(it.getString("id"),it.getString("name"),ImmichConfig(it.getString("url"),it.getString("key"),
                it.optBoolean("smart",true),it.optString("album").takeIf(String::isNotBlank)),it.getString("revision"))
        } }
    }
    private fun write(values: List<Connection>) {
        val rows = JSONArray(values.map { JSONObject().put("id",it.id).put("name",it.name).put("revision",it.revision)
            .put("url",it.config.serverUrl).put("key",it.config.apiKey).put("smart",it.config.smartSearch).put("album",it.config.albumId.orEmpty()) })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes = cipher.iv + cipher.doFinal(rows.toString().toByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object { private val lock = Mutex(); private const val ALIAS = "immich-extension-connections-v1" }
}
