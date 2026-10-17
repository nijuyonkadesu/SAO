package one.njk.sao.source

import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON records cross Binder; implementation classes and credentials do not. */
object SourceWire {
    fun strings(values: Collection<String>) = JSONArray(values.toList())
    fun strings(json: JSONArray?) = if (json == null) emptyList() else List(json.length()) { json.getString(it) }
    fun nullable(json: JSONObject, key: String) = if (json.isNull(key)) null else json.getString(key)
    private inline fun <reified T : Enum<T>> enums(json: JSONArray?) = strings(json).mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }.toSet()
    fun capabilities(value: SourceCapabilities) = JSONObject().put("modes", strings(value.modes.map { it.name }))
        .put("facets", strings(value.facets.map { it.name })).put("random", value.random).put("sequence", value.sequence)
        .put("defaultMode", value.defaultMode.name)
    fun capabilities(json: JSONObject): SourceCapabilities {
        val modes = enums<SearchMode>(json.optJSONArray("modes"))
        val default = SearchMode.entries.firstOrNull { it.name == json.optString("defaultMode") && it in modes }
            ?: modes.firstOrNull() ?: SearchMode.CONTEXT
        return SourceCapabilities(modes,enums<SearchFacet>(json.optJSONArray("facets")),
            json.optBoolean("random"),json.optBoolean("sequence"),default)
    }
    fun profile(value: ExtensionProfile) = JSONObject().put("id", value.id).put("name", value.name)
        .put("revision", value.revision).put("summary", value.summary)
    fun profile(json: JSONObject) = ExtensionProfile(json.getString("id"), json.getString("name"), json.getString("revision"), json.optString("summary"))
    fun person(value: SearchPerson) = JSONObject().put("id", value.id).put("name", value.name).put("source", value.sourceId)
    fun person(json: JSONObject) = SearchPerson(json.getString("id"), json.getString("name"), nullable(json, "source"))
    fun query(value: MediaQuery): JSONObject = JSONObject().put("text", value.text).put("filter", value.filter)
        .put("mode", value.mode.name).put("random", value.randomRequest).put("constraints", JSONObject()
            .put("source", value.constraints.sourceId).put("people", JSONArray(value.constraints.people.map(::person)))
            .put("country", value.constraints.country).put("state", value.constraints.state).put("city", value.constraints.city)
            .put("media", value.constraints.media.name))
    fun query(json: JSONObject): MediaQuery {
        val c = json.optJSONObject("constraints") ?: JSONObject()
        val people = c.optJSONArray("people") ?: JSONArray()
        return MediaQuery(json.optString("text"), nullable(json,"filter"),
            SearchMode.entries.firstOrNull { it.name == json.optString("mode") } ?: SearchMode.CONTEXT,
            SearchConstraints(nullable(c,"source"), List(people.length()) { person(people.getJSONObject(it)) },
                nullable(c,"country"), nullable(c,"state"), nullable(c,"city"),
                SearchMedia.entries.firstOrNull { it.name == c.optString("media") } ?: SearchMedia.ALL), nullable(json,"random"))
    }
    fun item(value: MediaItem) = JSONObject().put("id", value.id).put("title", value.title).put("asset", value.asset)
        .put("mime", value.mimeType).put("tags", strings(value.tags)).put("emojis", strings(value.emojis)).put("animated",value.animated)
        .put("remote", value.remote?.let { JSONObject().put("source",it.sourceId).put("asset",it.assetId).put("edited",it.edited).put("uploaded",it.uploadedAt).put("cacheKeys",JSONObject(it.cacheKeys)) })
    fun item(json: JSONObject): MediaItem {
        val ref = json.optJSONObject("remote")
        return MediaItem(json.getString("id"),json.getString("title"),json.optString("asset"),json.getString("mime"),
            strings(json.optJSONArray("tags")),strings(json.optJSONArray("emojis")),json.optBoolean("animated"),
            ref?.let {
                val keys = it.optJSONObject("cacheKeys")
                val variants = keys?.keys()?.asSequence()?.associateWith { name -> keys.getString(name) }.orEmpty()
                RemoteMedia(it.getString("source"),it.getString("asset"),it.optBoolean("edited"),nullable(it,"uploaded"),variants)
            })
    }
    fun page(value: MediaPage) = JSONObject().put("items", JSONArray(value.items.map(::item))).put("next",value.nextCursor)
    fun page(json: JSONObject): MediaPage {
        val items = json.getJSONArray("items"); require(items.length() <= 100)
        return MediaPage(List(items.length()) { item(items.getJSONObject(it)) },nullable(json,"next"))
    }
    fun sequence(value: SequencePage) = page(MediaPage(value.items)).put("newer",value.newer).put("older",value.older)
    fun sequence(json: JSONObject) = SequencePage(page(json).items,nullable(json,"newer"),nullable(json,"older"))
}
