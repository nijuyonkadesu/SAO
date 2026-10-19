package one.njk.sao.extension.moeview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import one.njk.sao.moeview.BooruApi
import one.njk.sao.moeview.data.*
import one.njk.sao.source.*
import org.json.JSONObject

/** Domain translation only: one requested page, and an opaque query/profile-bound continuation. */
class MoeviewRecords(private val store: MoeviewProfiles) {
    fun capabilities(config: MoeviewConfig)=SourceCapabilities(setOf(SearchMode.DESCRIPTION),random=config.board in setOf(Board.GELBOORU,Board.DANBOORU),
        defaultMode=SearchMode.DESCRIPTION,categories=listOf("All" to "","Smile" to "smile","Solo" to "solo",
            "Long hair" to "long_hair","Blue eyes" to "blue_eyes","Animal ears" to "animal_ears",
            "School uniform" to "school_uniform","Twintails" to "twintails","Scenery" to "scenery","Sky" to "sky").map { (label,tag) ->
                SourceCategory(tag.ifEmpty { "all" },label,MediaQuery(text=tag,mode=SearchMode.DESCRIPTION))
            })
    suspend fun page(profile: MoeviewProfile,source: String,query: MediaQuery,cursor: String?,api: BooruApi): MediaPage {
        val config=profile.config
        val normalized=capabilities(config).normalize(query)
        val fingerprint=digest(SourceWire.query(normalized).toString())
        val index=cursor?.let { token -> bound(profile,store.open(token)).let {
            if(it.getString("query")!=fingerprint) throw ExtensionFailure("INVALID_RESPONSE")
            it.getInt("page").also { page -> if(page !in 2..1_000_000) throw ExtensionFailure("INVALID_RESPONSE") }
        } } ?: 1
        val terms=listOf(config.tags,normalized.text).filter(String::isNotBlank).joinToString(" ").trim()
        if(terms.length>2048) throw ExtensionFailure("LIMIT_EXCEEDED")
        // Rating overrides belong to an explicit All-ratings profile, rather than silently escaping its scope.
        if(config.rating!=SfwMode.ALL && Regex("(?:^|\\s)-?rating:",RegexOption.IGNORE_CASE).containsMatchIn(terms)) throw ExtensionFailure("UNSUPPORTED")
        val random=normalized.randomRequest!=null
        val tags=if(random) "$terms ${if(config.board==Board.GELBOORU) "sort:random" else "order:random"}".trim() else terms
        val posts=api.search(config.board,tags,index,PAGE_SIZE,config.rating)
        if(posts.size>PAGE_SIZE) throw ExtensionFailure("INVALID_RESPONSE")
        val items=posts.mapNotNull { post ->
            val matches=when(config.rating) { SfwMode.SFW -> post.rating.isSfw; SfwMode.NSFW -> !post.rating.isSfw; SfwMode.ALL -> true }
            if(!matches) throw ExtensionFailure("INVALID_RESPONSE")
            record(profile,source,post)
        }
        val next=if(!random && posts.isNotEmpty()) store.seal(scope(profile).put("query",fingerprint).put("page",index+1)) else null
        return MediaPage(items,next)
    }
    private fun record(profile: MoeviewProfile,source: String,post: Post): MediaItem? {
        val config=profile.config
        val file=post.fileUrl ?: return null
        val extension=(post.fileExt ?: MediaType.extensionOf(file))?.lowercase() ?: return null
        val mime=when(extension) {
            "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; "avif" -> "image/avif"
            "mp4","m4v" -> "video/mp4"; "webm" -> "video/webm"; else -> return null
        }
        val preview=post.previewUrl ?: post.sampleUrl?.takeUnless { post.isVideo } ?: file.takeUnless { post.isVideo } ?: return null
        if(post.id<=0 || file.length+preview.length>1800 || !MoeviewRuntime.allowedMedia(config.board,file) || !MoeviewRuntime.allowedMedia(config.board,preview)) return null
        val animated=post.mediaType==MediaType.GIF || post.tags.any { it in setOf("animated_webp","animated_png") }
        val token=store.seal(scope(profile).put("id",post.id).put("file",file).put("preview",preview).put("mime",mime).put("animated",animated))
        val originalKey=digest("$file:${post.md5.orEmpty()}")
        val previewKey=if(animated && !post.isVideo) originalKey else digest(preview)
        val name=file.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotBlank() }?.take(256) ?: "${config.board.id}-${post.id}.$extension"
        return MediaItem("moeview_"+digest("$source:${post.id}"),"${config.board.label} · ${post.id}",token,mime,
            post.tags.take(24).map { it.take(64) },emptyList(),animated,
            RemoteMedia(source,post.id.toString(),false,post.createdAt?.let { java.time.Instant.ofEpochSecond(it).toString() },
                mapOf("original" to originalKey,"video" to originalKey,"preview" to previewKey,"raster" to if(post.isVideo) digest(preview) else originalKey)),name)
    }
    fun reference(profile: MoeviewProfile,token: String): JSONObject = bound(profile,store.open(token)).also {
        if(it.optInt("id")<=0) throw ExtensionFailure("INVALID_RESPONSE")
        if(!MoeviewRuntime.allowedMedia(profile.config.board,it.getString("file")) || !MoeviewRuntime.allowedMedia(profile.config.board,it.getString("preview"))) throw ExtensionFailure("INVALID_RESPONSE")
    }
    fun post(profile: MoeviewProfile,token: String): Post = reference(profile,token).let {
        Post(it.getInt("id"),Rating.UNKNOWN,"",emptyList(),"",0,fileUrl=it.getString("file"),previewUrl=it.getString("preview"),
            mediaType=when { it.getString("mime").startsWith("video/") -> MediaType.VIDEO; it.getBoolean("animated") -> MediaType.GIF; else -> MediaType.IMAGE })
    }
    fun variant(reference: JSONObject,kind: String): MediaKind=when(kind) {
        "original","video" -> MediaKind.FULL
        "preview" -> if(reference.getBoolean("animated") && !reference.getString("mime").startsWith("video/")) MediaKind.FULL else MediaKind.PREVIEW
        "raster" -> if(reference.getString("mime").startsWith("video/")) MediaKind.PREVIEW else MediaKind.FULL
        else -> throw ExtensionFailure("UNSUPPORTED")
    }
    private fun scope(profile: MoeviewProfile)=JSONObject().put("profile",profile.id).put("revision",profile.revision)
    private fun bound(profile: MoeviewProfile,value: JSONObject)=value.also {
        if(it.optString("profile")!=profile.id || it.optString("revision")!=profile.revision) throw ExtensionFailure("PROFILE_CHANGED")
    }
    companion object { const val PAGE_SIZE=20 }
}
