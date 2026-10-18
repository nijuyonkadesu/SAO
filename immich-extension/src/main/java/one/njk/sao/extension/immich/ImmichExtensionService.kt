package one.njk.sao.extension.immich

import android.content.Intent
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.immichprovider.ImmichAdapter
import one.njk.sao.immichprovider.ImmichMediaSequence
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject

class ImmichExtensionService : ExtensionService() {
    private val connections by lazy { ConnectionStore(this) }
    override fun acceptsCaller(uid: Int): String? = super.acceptsCaller(uid)?.takeIf { it in setOf("one.njk.sao","one.njk.sao.testing",packageName) }
    override fun descriptor() = JSONObject().put("name","Immich")
    override suspend fun execute(profile: String, operation: String, payload: JSONObject, caller: String): JSONObject {
        if (operation == ExtensionProtocol.PROFILES) {
            val all = connections.list()
            val fingerprint = digest(all.joinToString("|") { "${it.id}:${it.name}:${it.revision}" })
            val cursor = SourceWire.nullable(payload,"cursor")?.split(':')
            val start = if (cursor == null) 0 else {
                if (cursor.size != 2 || cursor[0] != fingerprint) throw ExtensionFailure("PROFILE_CHANGED")
                cursor[1].toIntOrNull()?.takeIf { it in 0..all.size } ?: throw ExtensionFailure("INVALID_RESPONSE")
            }
            val rows = all.drop(start).take(100)
            return JSONObject().put("profiles",JSONArray(rows.map { SourceWire.profile(it.publicProfile()) }))
                .put("next","$fingerprint:${start+rows.size}".takeIf { start+rows.size < all.size })
        }
        if (operation == ExtensionProtocol.IMPORT) {
            val legacy = payload.getJSONObject("configuration")
            val value = connections.save(profile,payload.getString("name"),ImmichConfig(legacy.getString("url"),legacy.getString("key"),
                legacy.optBoolean("smart",true),legacy.optString("album").takeIf(String::isNotBlank)))
            return SourceWire.profile(value.publicProfile())
        }
        val connection = connections.get(profile)
        if(payload.getString("revision") != connection.revision) throw ExtensionFailure("PROFILE_CHANGED")
        if (operation == ExtensionProtocol.DELETE_PROFILE) { connections.delete(profile); return JSONObject() }
        val identity = payload.getString("source")
        ImmichAdapter(connection.config,identity,FloatingDispatchers()).use { source ->
            return when (operation) {
                ExtensionProtocol.CAPABILITIES -> SourceWire.capabilities(source.searchModes().let { modes -> SourceCapabilities(modes,SearchFacet.entries.toSet(),random=true,sequence=true,
                    defaultMode=if(SearchMode.CONTEXT in modes) SearchMode.CONTEXT else SearchMode.FILENAME, categories=ImmichHomeCategories.values) })
                ExtensionProtocol.SEARCH -> SourceWire.page(source.page(SourceWire.query(payload.getJSONObject("query")),SourceWire.nullable(payload,"cursor")))
                ExtensionProtocol.FILTERS -> JSONObject().put("values",SourceWire.strings(source.filters()))
                ExtensionProtocol.PEOPLE -> source.people(payload.optString("name"),payload.optInt("page",1),identity).let {
                    JSONObject().put("people",JSONArray(it.people.map(SourceWire::person))).put("next",it.nextPage)
                }
                ExtensionProtocol.PLACES -> JSONObject().put("values",SourceWire.strings(source.places(PlaceLevel.valueOf(payload.getString("level")),
                    SourceWire.nullable(payload,"country"),SourceWire.nullable(payload,"state"),identity)))
                ExtensionProtocol.MEDIA -> {
                    val item = SourceWire.item(payload.getJSONObject("item"))
                    check(item.remote?.sourceId == identity)
                    val requested = payload.getString("kind")
                    val kind = when (requested) {
                        "preview" -> if (item.animated && !item.isVideo) "original" else "preview"
                        "raster" -> if (item.mimeType in setOf("image/png","image/webp")) "original" else "preview"
                        "video", "original" -> "original"
                        else -> throw ExtensionFailure("UNSUPPORTED")
                    }
                    requireNotNull(item.remote).let { resource(connection,kind,it.assetId,it.edited,caller) }
                }
                ExtensionProtocol.PERSON_MEDIA -> {
                    val person = SourceWire.person(payload.getJSONObject("person")); check(person.sourceId == identity)
                    resource(connection,"person",person.id,false,caller)
                }
                ExtensionProtocol.SEQUENCE_OPEN -> {
                    val sequence = source.openSequence(SourceWire.item(payload.getJSONObject("item"))) as ImmichMediaSequence
                    sequence.use { SourceWire.sequence(it.initial()).put("token",it.descriptor()) }
                }
                ExtensionProtocol.SEQUENCE_PAGE -> source.reopenSequence(payload.getJSONObject("token")).use {
                    SourceWire.sequence(it.page(SequenceDirection.valueOf(payload.getString("direction")),payload.getString("cursor")))
                }
                ExtensionProtocol.SEQUENCE_CLOSE -> JSONObject()
                else -> throw ExtensionFailure("UNSUPPORTED")
            }
        }
    }
    override fun failure(error: Exception): ExtensionFailure = when (error) {
        is retrofit2.HttpException -> ExtensionFailure("HTTP_${error.code()}",error.response()?.headers()?.get("Retry-After")?.take(128))
        is com.squareup.moshi.JsonDataException, is com.squareup.moshi.JsonEncodingException -> ExtensionFailure("INVALID_RESPONSE")
        is javax.net.ssl.SSLException -> ExtensionFailure("TLS")
        is java.io.IOException -> ExtensionFailure("NETWORK")
        else -> super.failure(error)
    }
    private fun resource(connection: ConnectionStore.Connection, kind: String, id: String, edited: Boolean, caller: String): JSONObject {
        val uri = android.net.Uri.Builder().scheme("content").authority("$packageName.media")
            .appendPath(connection.id).appendPath(connection.revision).appendPath(kind).appendPath(id)
            .appendQueryParameter("edited",edited.toString()).build()
        val scope = android.net.Uri.Builder().scheme("content").authority("$packageName.media")
            .appendPath(connection.id).appendPath(connection.revision).build()
        grantUriPermission(caller,scope,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        return JSONObject().put("uri",uri.toString())
    }
}
