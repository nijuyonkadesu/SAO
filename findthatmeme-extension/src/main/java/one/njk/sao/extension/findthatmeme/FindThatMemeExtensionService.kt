package one.njk.sao.extension.findthatmeme

import android.content.Intent
import android.net.Uri
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject

class FindThatMemeExtensionService : ExtensionService() {
    private val runtime by lazy { FindThatMemeRuntime.get(this) }
    override fun descriptor(): JSONObject = JSONObject().put("name", "FindThatMeme")
    override fun failure(error: Exception): ExtensionFailure = FindThatMemeRuntime.failure(error)

    override suspend fun execute(profile: String, operation: String, payload: JSONObject, caller: String): JSONObject {
        val store = runtime.profiles
        if (operation == ExtensionProtocol.PROFILES) {
            val all = store.list()
            val fingerprint = digest(all.joinToString("|") { "${it.id}:${it.name}:${it.revision}" })
            val cursor = SourceWire.nullable(payload, "cursor")?.split(':')
            val offset = if (cursor == null) 0 else {
                if (cursor.size != 2 || cursor[0] != fingerprint) throw ExtensionFailure("PROFILE_CHANGED")
                cursor[1].toIntOrNull()?.takeIf { it in 0..all.size } ?: throw ExtensionFailure("INVALID_RESPONSE")
            }
            val rows = all.drop(offset).take(100)
            return JSONObject().put("profiles", JSONArray(rows.map { SourceWire.profile(it.publicProfile()) }))
                .put("next", "$fingerprint:${offset + rows.size}".takeIf { offset + rows.size < all.size })
        }
        val row = store.get(profile)
        if (payload.getString("revision") != row.revision) throw ExtensionFailure("PROFILE_CHANGED")
        if (operation == ExtensionProtocol.DELETE_PROFILE) {
            store.delete(profile, row.revision)
            return JSONObject()
        }
        val records = FindThatMemeRecords(store)
        return when (operation) {
            ExtensionProtocol.CAPABILITIES -> SourceWire.capabilities(records.capabilities())
            ExtensionProtocol.FILTERS -> JSONObject().put("values", JSONArray())
            ExtensionProtocol.SEARCH -> SourceWire.page(records.page(row, payload.getString("source"),
                SourceWire.query(payload.getJSONObject("query")), SourceWire.nullable(payload, "cursor"), runtime.api))
            ExtensionProtocol.MEDIA -> {
                val item = SourceWire.item(payload.getJSONObject("item"))
                if (item.remote?.sourceId != payload.getString("source")) throw ExtensionFailure("PROFILE_CHANGED")
                val ref = records.reference(row, item.asset)
                if (ref.getString("id") != item.remote?.assetId || ref.getString("mime") != item.mimeType) throw ExtensionFailure("INVALID_RESPONSE")
                val kind = payload.getString("kind")
                records.variant(ref, kind)
                val base = Uri.Builder().scheme("content").authority("$packageName.media").appendPath(row.id).appendPath(row.revision)
                val prefix = base.build()
                val uri = base.appendPath(kind).appendPath(item.asset).build()
                grantUriPermission(caller, prefix, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                JSONObject().put("uri", uri.toString())
            }
            else -> throw ExtensionFailure("UNSUPPORTED")
        }
    }
}
