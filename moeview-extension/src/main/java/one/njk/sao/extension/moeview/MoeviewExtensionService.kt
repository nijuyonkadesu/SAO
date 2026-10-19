package one.njk.sao.extension.moeview

import android.content.Intent
import android.net.Uri
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject

class MoeviewExtensionService : ExtensionService() {
    private val runtime by lazy { MoeviewRuntime.get(this) }
    override fun acceptsCaller(uid: Int)=super.acceptsCaller(uid)?.takeIf { it in setOf("one.njk.sao","one.njk.sao.testing",packageName) }
    override fun descriptor()=JSONObject().put("name","Moeview")
    override fun failure(error: Exception)=MoeviewRuntime.failure(error)
    override suspend fun execute(profile: String,operation: String,payload: JSONObject,caller: String): JSONObject {
        val store=runtime.profiles
        if(operation==ExtensionProtocol.PROFILES) {
            val all=store.list(); val fingerprint=digest(all.joinToString("|") { "${it.id}:${it.name}:${it.revision}" })
            val cursor=SourceWire.nullable(payload,"cursor")?.split(':')
            val offset=if(cursor==null) 0 else {
                if(cursor.size!=2 || cursor[0]!=fingerprint) throw ExtensionFailure("PROFILE_CHANGED")
                cursor[1].toIntOrNull()?.takeIf { it in 0..all.size } ?: throw ExtensionFailure("INVALID_RESPONSE")
            }
            val rows=all.drop(offset).take(100)
            return JSONObject().put("profiles",JSONArray(rows.map { SourceWire.profile(it.publicProfile()) }))
                .put("next","$fingerprint:${offset+rows.size}".takeIf { offset+rows.size<all.size })
        }
        val row=store.get(profile)
        if(payload.getString("revision")!=row.revision) throw ExtensionFailure("PROFILE_CHANGED")
        if(operation==ExtensionProtocol.DELETE_PROFILE) {
            store.delete(profile,row.revision); runtime.invalidate(profile); return JSONObject()
        }
        val source=payload.getString("source"); val records=MoeviewRecords(store)
        return when(operation) {
            ExtensionProtocol.CAPABILITIES -> SourceWire.capabilities(records.capabilities(row.config))
            ExtensionProtocol.SEARCH -> runtime.use(row) { SourceWire.page(records.page(row,source,SourceWire.query(payload.getJSONObject("query")),SourceWire.nullable(payload,"cursor"),it)) }
            ExtensionProtocol.SUGGESTIONS -> runtime.use(row) { SourceWire.suggestions(MoeviewSuggestions.query(row,
                SourceWire.query(payload.getJSONObject("query")),payload.getInt("cursor"),it)) }
            ExtensionProtocol.FILTERS -> JSONObject().put("values",JSONArray())
            ExtensionProtocol.MEDIA -> {
                val item=SourceWire.item(payload.getJSONObject("item"))
                if(item.remote?.sourceId!=source) throw ExtensionFailure("PROFILE_CHANGED")
                val reference=records.reference(row,item.asset)
                if(reference.getInt("id").toString()!=item.remote?.assetId || reference.getString("mime")!=item.mimeType) throw ExtensionFailure("INVALID_RESPONSE")
                val kind=payload.getString("kind"); records.variant(reference,kind)
                val uri=Uri.Builder().scheme("content").authority("$packageName.media").appendPath(row.id).appendPath(row.revision).appendPath(kind).appendPath(item.asset).build()
                val scope=Uri.Builder().scheme("content").authority("$packageName.media").appendPath(row.id).appendPath(row.revision).build()
                grantUriPermission(caller,scope,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                JSONObject().put("uri",uri.toString())
            }
            else -> throw ExtensionFailure("UNSUPPORTED")
        }
    }
}
