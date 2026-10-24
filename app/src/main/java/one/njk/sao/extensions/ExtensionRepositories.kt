package one.njk.sao.extensions

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import one.njk.sao.database.SettingsDataStore
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Repository order is deterministic; the first repository owns duplicate package names. */
@Singleton
class ExtensionRepositories @Inject constructor(private val settings: SettingsDataStore) {
    internal val urls=settings.extensionRepositoriesFlow().map { saved ->
        (saved ?: setOf(ExtensionCatalog.URL)).sortedWith(compareBy<String> { it!=ExtensionCatalog.URL }.thenBy { it })
    }.distinctUntilChanged()
    internal suspend fun add(url: String) = settings.extensionRepository(RepositoryUrl.normalize(url),true,setOf(ExtensionCatalog.URL))
    internal suspend fun remove(url: String) = settings.extensionRepository(url,false,setOf(ExtensionCatalog.URL))
}

/** Accept a direct HTTPS index or the usual GitHub repository/tree/blob links. No embedded credentials. */
internal object RepositoryUrl {
    fun label(url: String): String {
        val uri=URI(url)
        return if(uri.host=="raw.githubusercontent.com") uri.path.trim('/').split('/').take(2).joinToString("/")
            else uri.host+uri.path.removeSuffix("/index.json")
    }
    fun https(value: String): URI = URI(value).also {
        require(value.length in 1..2048 && it.scheme=="https" && !it.host.isNullOrBlank() &&
            it.userInfo==null && it.fragment==null && (it.port==-1 || it.port in 1..65535))
    }
    fun normalize(input: String): String {
        val uri=https(input.trim())
        if(uri.host.equals("github.com",true) && uri.query==null) {
            val parts=uri.path.trim('/').split('/')
            require(parts.size>=2 && parts.take(2).all { it.matches(Regex("[A-Za-z0-9_.-]+")) })
            val owner=parts[0]; val repo=parts[1].removeSuffix(".git")
            val path=when {
                parts.size==2 -> "repo/index.json"
                parts.size>=4 && parts[2]=="blob" -> parts.drop(3).joinToString("/")
                parts.size>=4 && parts[2]=="tree" -> parts.drop(3).joinToString("/").trimEnd('/')+"/index.json"
                else -> throw IllegalArgumentException("Use an index.json URL or a GitHub repository link")
            }
            return https("https://raw.githubusercontent.com/$owner/$repo/$path").toASCIIString()
        }
        val port=if(uri.port in setOf(-1,443)) "" else ":${uri.port}"
        return URI("https://${uri.host.lowercase()}$port${uri.rawPath.ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
    }
}
