package one.njk.sao.extension.moeview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.SfwMode
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MoeviewConfig(val board: Board=Board.KONACHAN,val rating: SfwMode=SfwMode.SFW,
    val tags: String="",val proxy: Boolean=true,val login: String="",val apiKey: String="",val clearance: String="",val browserAgent: String="") {
    val authenticated get()=login.isNotBlank() && apiKey.isNotBlank()
    val supportsAuth get()=board in setOf(Board.DANBOORU,Board.GELBOORU)
    fun validate() {
        require(tags.length<=1024 && login.length<=128 && apiKey.length<=512 && clearance.length<=8192 && browserAgent.length<=1024)
        require(!clearance.contains('\r') && !clearance.contains('\n') && !browserAgent.contains('\r') && !browserAgent.contains('\n'))
        require(clearance.isEmpty() || MoeviewAuthentication.cloudflareCookies(clearance)==clearance)
        require(login.isBlank()==apiKey.isBlank() && (!authenticated || supportsAuth))
        require(rating==SfwMode.ALL || !Regex("(?:^|\\s)-?rating:",RegexOption.IGNORE_CASE).containsMatchIn(tags))
        if(board==Board.GELBOORU && authenticated) require(login.toLongOrNull()?.let { it>0 }==true)
    }
    fun json()=JSONObject().put("board",board.name).put("rating",rating.name).put("tags",tags)
        .put("proxy",proxy).put("login",login).put("key",apiKey).put("clearance",clearance).put("agent",browserAgent)
    override fun toString()="MoeviewConfig(board=$board, rating=$rating, authenticated=$authenticated)"
    companion object {
        fun read(value: JSONObject)=MoeviewConfig(Board.valueOf(value.getString("board")),SfwMode.valueOf(value.getString("rating")),
            value.optString("tags"),value.optBoolean("proxy",true),value.optString("login"),value.optString("key"),value.optString("clearance"),value.optString("agent")).also { it.validate() }
    }
}
data class MoeviewProfile(val id: String,val name: String,val revision: String,val config: MoeviewConfig) {
    fun publicProfile()=ExtensionProfile(id,name,revision,"${config.board.label} · ${when(config.rating) { SfwMode.SFW -> "Safe only"; SfwMode.NSFW -> "Non-safe only"; SfwMode.ALL -> "All ratings" }}")
}

/** Private profiles and authenticated opaque references survive process death, with no result/media cache. */
class MoeviewProfiles(context: Context) {
    private val app=context.applicationContext
    private val file=AtomicFile(File(app.noBackupFilesDir,"moeview-profiles-v1"))
    private val lock=Mutex()
    private val key: SecretKey by lazy {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun list(): List<MoeviewProfile> = withContext(Dispatchers.IO) { lock.withLock { read() } }
    suspend fun get(id: String)=list().firstOrNull { it.id==id } ?: throw ExtensionFailure("PROFILE_MISSING")
    suspend fun save(id: String,name: String,config: MoeviewConfig): MoeviewProfile = withContext(Dispatchers.IO) { lock.withLock {
        UUID.fromString(id); require(name.isNotBlank() && name.length<=100); config.validate()
        val rows=read(); val prior=rows.firstOrNull { it.id==id }
        val saved=MoeviewProfile(id,name.trim(),prior?.revision?.takeIf { prior.config==config } ?: UUID.randomUUID().toString(),config)
        write(rows.filterNot { it.id==id }+saved)
        if(prior!=null && prior.revision!=saved.revision) revoke(prior)
        saved
    } }
    suspend fun delete(id: String,revision: String) = withContext(Dispatchers.IO) { lock.withLock {
        val rows=read(); val row=rows.firstOrNull { it.id==id } ?: throw ExtensionFailure("PROFILE_MISSING")
        if(row.revision!=revision) throw ExtensionFailure("PROFILE_CHANGED")
        write(rows.filterNot { it.id==id }); revoke(row)
    } }
    fun seal(value: JSONObject): String=Base64.encodeToString(encrypt(value.toString().toByteArray()),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun open(token: String): JSONObject = try {
        require(token.length<=12_000)
        JSONObject(String(decrypt(Base64.decode(token,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)),Charsets.UTF_8))
    } catch(_: Exception) { throw ExtensionFailure("INVALID_RESPONSE") }
    private fun encrypt(value: ByteArray): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
        return cipher.iv+cipher.doFinal(value)
    }
    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size>=29)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,value.copyOfRange(0,12))); doFinal(value.copyOfRange(12,value.size))
        }
    }
    private fun read(): List<MoeviewProfile> {
        if(!file.baseFile.exists()) return emptyList()
        require(file.baseFile.length()<=8*1024*1024)
        val rows=JSONArray(String(decrypt(file.readFully()),Charsets.UTF_8))
        return List(rows.length()) { rows.getJSONObject(it).let { r -> MoeviewProfile(r.getString("id"),r.getString("name"),r.getString("revision"),MoeviewConfig.read(r.getJSONObject("config"))) } }
    }
    private fun write(rows: List<MoeviewProfile>) {
        val bytes=encrypt(JSONArray(rows.map { JSONObject().put("id",it.id).put("name",it.name).put("revision",it.revision).put("config",it.config.json()) }).toString().toByteArray())
        val output=file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch(error: Exception) { file.failWrite(output); throw error }
    }
    private fun revoke(row: MoeviewProfile) {
        val uri=Uri.Builder().scheme("content").authority("${app.packageName}.media").appendPath(row.id).appendPath(row.revision).build()
        app.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    companion object { private const val ALIAS="moeview-source-v1" }
}
