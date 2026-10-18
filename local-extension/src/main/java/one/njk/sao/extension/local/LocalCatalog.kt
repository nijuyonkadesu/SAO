package one.njk.sao.extension.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import one.njk.sao.source.*
import org.json.JSONObject
import java.time.Instant

/** Domain paging uses stable saved-time/row-ID cursors; SAO decides when to request each page. */
class LocalCatalog(private val store: LocalStore, private val library: Library, private val source: String) {
    fun item(asset: LocalAsset): MediaItem {
        val fullPreview=asset.animated && !asset.mime.startsWith("video/")
        return MediaItem("local_${digest(source).take(12)}_${asset.id}_${asset.signature.take(16)}",asset.displayTitle.take(160),"",asset.mime,
            asset.effectiveTags.take(20).map { it.take(40) },asset.effectiveTags.filter { isEmoji(it) }.take(20),asset.animated,
            RemoteMedia(source,asset.id.toString(),false,asset.modified.takeIf { it>0 }?.let { Instant.ofEpochMilli(it).toString() },
                mapOf("original" to "original","video" to "original","preview" to if(fullPreview) "original" else "preview",
                    "raster" to if(asset.mime in setOf("image/png","image/webp")) "original" else "preview")), filename=asset.filename)
    }
    fun page(query: MediaQuery, cursor: String?): MediaPage {
        if(query.randomRequest!=null && cursor!=null) return MediaPage(emptyList())
        val key=digest(SourceWire.query(query).toString()+library.revision)
        val boundary=cursor?.let { decode(it,key) }
        val rows=store.search(library.id,query,boundary,limit=61)
        val shown=rows.take(60)
        return MediaPage(shown.map(::item),if(rows.size>60 && query.randomRequest==null) encode(shown.last(),key) else null)
    }
    fun open(anchor: MediaItem): JSONObject {
        require(anchor.remote?.sourceId==source)
        val asset=store.asset(requireNotNull(anchor.remote).assetId.toLong(),library.id)
        val point=asset.modified to asset.id
        val before=store.search(library.id,MediaQuery(),point,newer=true,limit=21)
        val after=store.search(library.id,MediaQuery(),point,limit=21)
        val shown=before.take(20).reversed()+asset+after.take(20)
        return SourceWire.sequence(SequencePage(shown.map(::item),
            if(before.size>20) encode(shown.first(),library.revision) else null,
            if(after.size>20) encode(shown.last(),library.revision) else null)).put("token",JSONObject().put("revision",library.revision))
    }
    fun sequence(token: JSONObject, direction: SequenceDirection, cursor: String): SequencePage {
        if(token.getString("revision")!=library.revision) throw ExtensionFailure("PROFILE_CHANGED")
        val newer=direction==SequenceDirection.NEWER
        val boundary=decode(cursor,library.revision)
        val rows=store.search(library.id,MediaQuery(),boundary,newer,41)
        val shown=rows.take(40).let { if(newer) it.reversed() else it }
        return SequencePage(shown.map(::item),
            if(newer && rows.size>40) encode(shown.first(),library.revision) else null,
            if(!newer && rows.size>40) encode(shown.last(),library.revision) else null)
    }
    private fun encode(asset: LocalAsset, key: String)="${asset.modified}:${asset.id}:$key"
    private fun decode(value: String, key: String): Pair<Long,Long> {
        val parts=value.split(':'); if(parts.size!=3 || parts[2]!=key) throw ExtensionFailure("INVALID_RESPONSE")
        return (parts[0].toLongOrNull() ?: throw ExtensionFailure("INVALID_RESPONSE")) to
            (parts[1].toLongOrNull()?.takeIf { it>0 } ?: throw ExtensionFailure("INVALID_RESPONSE"))
    }
    companion object { fun isEmoji(value: String)=value.codePoints().anyMatch { it in 0x1f000..0x1faff || it in 0x2600..0x27ff } }
}

class LocalExtensionService : ExtensionService() {
    private val store by lazy { LocalStore.get(this) }
    private val textSessionHolder=lazy { ClipTextSession(applicationContext) }
    private val textSession by textSessionHolder
    private val contextSearch by lazy { LocalContextSearch(store,textSession) }
    override fun onDestroy() { if(textSessionHolder.isInitialized()) textSession.close(); super.onDestroy() }
    override fun acceptsCaller(uid: Int) = super.acceptsCaller(uid)?.takeIf { it in setOf("one.njk.sao","one.njk.sao.testing",packageName) }
    override fun descriptor()=JSONObject().put("name","Local Sticker")
    override suspend fun execute(profile: String, operation: String, payload: JSONObject, caller: String): JSONObject {
        if(operation==ExtensionProtocol.PROFILES) {
            val profiles=store.libraries().map { library ->
                val status=store.status(library.id)
                ExtensionProfile(library.id,library.name,library.revision,getString(R.string.profile_summary,library.folders.size,status.total))
            }
            val fingerprint=digest(profiles.joinToString { "${it.id}:${it.name}:${it.revision}" })
            val cursor=SourceWire.nullable(payload,"cursor")?.split(':')
            val start=if(cursor==null) 0 else {
                if(cursor.size!=2 || cursor[0]!=fingerprint) throw ExtensionFailure("PROFILE_CHANGED")
                cursor[1].toIntOrNull()?.takeIf { it in 0..profiles.size } ?: throw ExtensionFailure("INVALID_RESPONSE")
            }
            return JSONObject().put("profiles",org.json.JSONArray(profiles.drop(start).take(100).map(SourceWire::profile)))
                .put("next","$fingerprint:${start+100}".takeIf { start+100<profiles.size })
        }
        val library=store.library(profile)
        if(payload.getString("revision")!=library.revision) throw ExtensionFailure("PROFILE_CHANGED")
        if(operation==ExtensionProtocol.DELETE_PROFILE) {
            FolderIndexer.cancel(this,profile); store.delete(profile); releaseUnusedGrants(this,store)
            revokeUriPermission(mediaScope(this,library),Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return JSONObject()
        }
        val source=payload.getString("source")
        val catalog=LocalCatalog(store,library,source)
        return when(operation) {
            ExtensionProtocol.CAPABILITIES -> {
                FolderIndexer.schedule(this,library)
                val updates=store.changesUri(library)
                grantUriPermission(caller,updates,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val context=library.contextSearch && ClipModels.ready(this,ClipCatalog.get(library.contextModel))
                val modes=setOf(SearchMode.FILENAME,SearchMode.DESCRIPTION,SearchMode.PATH,SearchMode.OCR)+(if(context) setOf(SearchMode.CONTEXT) else emptySet())
                SourceWire.capabilities(SourceCapabilities(modes,setOf(SearchFacet.MEDIA,SearchFacet.EMOJI_TAGS),random=true,sequence=true,
                    defaultMode=if(context) SearchMode.CONTEXT else SearchMode.DESCRIPTION,changesUri=updates.toString(),categories=LocalHomeCategories.values))
            }
            ExtensionProtocol.SEARCH -> {
                FolderIndexer.schedule(this,library)
                val query=SourceWire.query(payload.getJSONObject("query")); val cursor=SourceWire.nullable(payload,"cursor")
                if(query.mode==SearchMode.CONTEXT && query.text.isNotBlank() && query.randomRequest==null) {
                    if(!library.contextSearch || !ClipModels.ready(this,ClipCatalog.get(library.contextModel))) throw ExtensionFailure("UNSUPPORTED")
                    SourceWire.page(contextSearch.page(library,source,query,cursor))
                } else SourceWire.page(catalog.page(query,cursor))
            }
            ExtensionProtocol.FILTERS -> JSONObject().put("values",SourceWire.strings(store.emojiFilters(profile)))
            ExtensionProtocol.SEQUENCE_OPEN -> catalog.open(SourceWire.item(payload.getJSONObject("item")))
            ExtensionProtocol.SEQUENCE_PAGE -> SourceWire.sequence(catalog.sequence(payload.getJSONObject("token"),SequenceDirection.valueOf(payload.getString("direction")),payload.getString("cursor")))
            ExtensionProtocol.SEQUENCE_CLOSE -> JSONObject()
            ExtensionProtocol.MEDIA -> {
                val item=SourceWire.item(payload.getJSONObject("item")); require(item.remote?.sourceId==source)
                val asset=store.asset(requireNotNull(item.remote).assetId.toLong(),profile)
                if(item.id!=catalog.item(asset).id) throw ExtensionFailure("MEDIA_CHANGED")
                val requested=payload.getString("kind")
                val variant=when(requested) {
                    "original","video" -> "original"
                    "preview" -> if(asset.animated && !asset.mime.startsWith("video/")) "original" else "preview"
                    "raster" -> if(asset.mime in setOf("image/png","image/webp")) "original" else "preview"
                    else -> throw ExtensionFailure("UNSUPPORTED")
                }
                val scope=mediaScope(this,library)
                grantUriPermission(caller,scope,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                JSONObject().put("uri",scope.buildUpon().appendPath(asset.id.toString()).appendPath(asset.signature).appendPath(variant).build().toString())
            }
            else -> throw ExtensionFailure("UNSUPPORTED")
        }
    }
}
internal fun mediaScope(context: Context, library: Library): Uri = Uri.Builder().scheme("content").authority("${context.packageName}.media").appendPath(library.id).appendPath(library.revision).build()
internal fun releaseUnusedGrants(context: Context, store: LocalStore) {
    val used=store.libraries().flatMap { it.folders }.filter { it.kind==CollectionKind.TREE }.map { it.uri }.toSet()+store.selectedUris()+ModelStorage.usedTrees(context)
    context.contentResolver.persistedUriPermissions.filter { it.uri.toString() !in used }.forEach {
        try { context.contentResolver.releasePersistableUriPermission(it.uri,(if(it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if(it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)) } catch (_: SecurityException) { }
    }
}
