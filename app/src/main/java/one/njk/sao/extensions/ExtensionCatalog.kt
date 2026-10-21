package one.njk.sao.extensions

import org.json.JSONObject
import java.net.URI

data class ExtensionArtifact(val abi: String, val url: String, val sha256: String, val size: Long)
data class CatalogExtension(val id: String, val packageName: String, val name: String, val description: String,
    val versionCode: Long, val versionName: String, val apiVersion: Int, val minSdk: Int,
    val signerSha256: String, val channel: String, val artifacts: List<ExtensionArtifact>, val iconUrl: String? = null) {
    fun artifact(abis: List<String>): ExtensionArtifact? = abis.firstNotNullOfOrNull { abi -> artifacts.firstOrNull { it.abi==abi } }
        ?: artifacts.firstOrNull { it.abi=="universal" }
}

/** A distribution catalog does not replace runtime profile/capability negotiation. */
internal object ExtensionCatalog {
    const val URL="https://raw.githubusercontent.com/nijuyonkadesu/SAO-extensions/repo/index.json"
    const val MAX_APK_BYTES=128L*1024*1024
    private val digest=Regex("[0-9a-f]{64}")
    fun decode(text: String, publisher: String): List<CatalogExtension> {
        val root=JSONObject(text)
        require(root.getInt("schemaVersion")==1)
        val rows=root.getJSONArray("extensions")
        require(rows.length()<=64)
        val result=(0 until rows.length()).map { index ->
            val row=rows.getJSONObject(index)
            fun label(key: String, maximum: Int)=row.getString(key).also { require(it.isNotBlank() && it.length<=maximum && '\n' !in it && '\r' !in it) }
            val id=label("id",80).also { require(it.matches(Regex("[a-z0-9_]+"))) }
            val pkg=label("packageName",180).also { require(it=="one.njk.sao.extension.$id") }
            val signer=row.getString("signerSha256").also { require(it.matches(digest) && it==publisher) }
            val files=row.getJSONArray("artifacts").also { require(it.length() in 1..8) }
            val artifacts=(0 until files.length()).map { position -> files.getJSONObject(position).let { file ->
                ExtensionArtifact(file.getString("abi").also { require(it in setOf("universal","arm64-v8a","armeabi-v7a","x86_64","x86")) },
                    file.getString("url").also(::validateApkUrl),file.getString("sha256").also { require(it.matches(digest)) },
                    file.getLong("size").also { require(it in 1..MAX_APK_BYTES) })
            } }
            require(artifacts.map { it.abi }.distinct().size==artifacts.size)
            CatalogExtension(id,pkg,label("name",100),label("description",600),row.getLong("versionCode").also { require(it>0) },
                label("versionName",60),row.getInt("apiVersion").also { require(it>0) },row.getInt("minSdk").also { require(it>=21) },
                signer,row.optString("channel","stable").also { require(it in setOf("stable","development")) },artifacts,
                row.optString("iconUrl").takeIf { it=="https://raw.githubusercontent.com/nijuyonkadesu/SAO-extensions/repo/icons/$id.svg" })
        }
        require(result.map { it.packageName }.distinct().size==result.size)
        return result
    }
    private fun validateApkUrl(value: String) {
        val uri=URI(value)
        require(uri.scheme=="https" && uri.host=="github.com" && uri.userInfo==null && uri.port==-1 && uri.fragment==null && uri.query==null)
        require(uri.rawPath.matches(Regex("/nijuyonkadesu/SAO-extensions/releases/download/[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+\\.apk")))
    }
}

internal enum class CatalogIssue { NETWORK, INVALID, DOWNLOAD, SIGNATURE, INCOMPATIBLE, INSTALL }
internal class CatalogFailure(val issue: CatalogIssue, cause: Throwable?=null): Exception(issue.name,cause)
