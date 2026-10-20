package one.njk.sao.floating

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.njk.sao.source.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** Generic adapter. All source-specific API shapes, credentials and URLs stay across the boundary. */
class ExtensionMediaSource(private val context: Context, private val profile: SourceProfile,
    private val extensions: SourceExtensions, private val cache: SourceMediaCache) : ManagedMediaSource {
    private val config = profile.configuration as ExtensionConfig
    private val client = ExtensionClient(context,config.component)
    @Volatile private var closed = false
    private val capabilityLock = Mutex()
    private var supported: SourceCapabilities? = null
    private var discoveryQuery: MediaQuery? = null
    private val capabilityState = MutableStateFlow(SourceCapabilities())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val changes = flow {
        val uri=loadCapabilities().changesUri?.let(::validate) ?: return@flow
        // A newly visible browsing session may reconcile local folders once. Index notifications
        // within that session keep the query marker and never cause another discovery pass.
        discoveryQuery=null
        emitAll(kotlinx.coroutines.flow.callbackFlow {
            val observer=object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            context.contentResolver.registerContentObserver(uri,true,observer)
            trySend(Unit)
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        })
    }
    override val capabilities = flow {
        try { loadCapabilities() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A later explicit search retries and updates this flow. */ }
        emitAll(capabilityState)
    }
    private suspend fun loadCapabilities(): SourceCapabilities = capabilityLock.withLock {
        supported ?: SourceWire.capabilities(call(ExtensionProtocol.CAPABILITIES)).also { supported = it; capabilityState.value = it }
    }
    private suspend fun request(operation: String, value: JSONObject): JSONObject {
        check(!closed)
        extensions.requireInstalled(config)
        return client.request(config.profile,operation,value.put("source",profile.identity).put("revision",config.revision))
    }
    private suspend fun call(operation: String, value: JSONObject = JSONObject()): JSONObject {
        check(!closed)
        try {
            val result = ReadRetry.run { request(operation,value) }
            check(!closed)
            return result
        } catch (error: ExtensionFailure) { throw SourceReadException(error.messageResource(),error) }
    }
    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage {
        val normalized = loadCapabilities().normalize(query)
        val discover=cursor==null && discoveryQuery!=normalized
        var next = cursor
        val seen = mutableSetOf<String>()
        // Empty filtered pages are advanced by the host, serially and cancellably.
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = SourceWire.page(call(ExtensionProtocol.SEARCH,JSONObject().put("query",SourceWire.query(normalized)).put("cursor",next).put("discover",discover)))
            discoveryQuery=normalized
            check(page.items.all { it.remote?.sourceId == profile.identity }) { "Invalid source identity" }
            if (page.items.isNotEmpty() || page.nextCursor == null) return page
            check(page.nextCursor != next && seen.add(requireNotNull(page.nextCursor))) { "Repeated source cursor" }
            next = page.nextCursor
        }
    }
    override suspend fun filters() = if (SearchFacet.EMOJI_TAGS in loadCapabilities().facets)
        SourceWire.strings(call(ExtensionProtocol.FILTERS).optJSONArray("values")) else emptyList()
    override suspend fun searchModes() = loadCapabilities().modes
    override suspend fun suggestions(query: MediaQuery, cursor: Int, expectedSourceId: String?): SearchSuggestions {
        check(expectedSourceId==null || expectedSourceId==profile.identity)
        if(query.mode !in loadCapabilities().suggestionModes) return SearchSuggestions.empty(cursor)
        // Obsolete typeahead is cancelled rather than retried; the next edit starts a fresh request.
        return try { SourceWire.suggestions(request(ExtensionProtocol.SUGGESTIONS,
            JSONObject().put("query",SourceWire.query(query)).put("cursor",cursor))).validate(query.text,cursor) }
        catch(error: ExtensionFailure) { throw SourceReadException(error.messageResource(),error) }
    }
    override suspend fun people(name: String, page: Int, expectedSourceId: String?): PeoplePage {
        check(expectedSourceId == null || expectedSourceId == profile.identity)
        val reply = call(ExtensionProtocol.PEOPLE,JSONObject().put("name",name).put("page",page))
        val rows = reply.getJSONArray("people")
        return PeoplePage(List(rows.length()) { SourceWire.person(rows.getJSONObject(it)) },reply.optInt("next").takeIf { it > 0 })
    }
    override suspend fun places(level: PlaceLevel, country: String?, state: String?, expectedSourceId: String?): List<String> {
        check(expectedSourceId == null || expectedSourceId == profile.identity)
        return SourceWire.strings(call(ExtensionProtocol.PLACES,JSONObject().put("level",level.name).put("country",country).put("state",state)).optJSONArray("values"))
    }
    private fun validate(uri: String): Uri = Uri.parse(uri).also {
        require(it.scheme == "content" && context.packageManager.resolveContentProvider(it.authority.orEmpty(),0)?.packageName == config.packageName)
    }
    private suspend fun file(item: MediaItem, kind: String): File {
        check(item.remote?.sourceId == profile.identity && !closed)
        val keys = requireNotNull(item.remote).cacheKeys
        val variant = keys[kind] ?: kind
        val fullSize = kind != "preview" || variant == (keys["original"] ?: "original")
        val maxBytes = if (fullSize) 64L*1024*1024 else 8L*1024*1024
        return mediaRead { cache.get(profile.identity,"${item.id}:$variant",maxBytes) {
            validate(request(ExtensionProtocol.MEDIA,JSONObject().put("item",SourceWire.item(item)).put("kind",kind)).getString("uri"))
        } }
    }
    private suspend fun <T> mediaRead(block: suspend () -> T): T = try { block() }
        catch (error: ExtensionFailure) { throw SourceReadException(error.messageResource(),error) }
    override suspend fun preview(item: MediaItem): Any = file(item,"preview")
    override suspend fun video(item: MediaItem): File? = if (item.isVideo) file(item,"video") else null
    override suspend fun original(item: MediaItem): InputStream = file(item,"original").inputStream()
    override suspend fun raster(item: MediaItem): InputStream = file(item,"raster").inputStream()
    override suspend fun personPreview(person: SearchPerson): Any {
        check(person.sourceId == profile.identity)
        return mediaRead { cache.get(profile.identity,"person:${person.id}",2L*1024*1024) {
            validate(request(ExtensionProtocol.PERSON_MEDIA,JSONObject().put("person",SourceWire.person(person))).getString("uri"))
        } }
    }
    override suspend fun openSequence(anchor: MediaItem): MediaSequence {
        check(loadCapabilities().sequence)
        val initial = call(ExtensionProtocol.SEQUENCE_OPEN,JSONObject().put("item",SourceWire.item(anchor)))
        val token = initial.getJSONObject("token")
        return object : MediaSequence {
            private var sequenceClosed = false
            override suspend fun initial(): SequencePage { check(!sequenceClosed); return SourceWire.sequence(initial) }
            override suspend fun page(direction: SequenceDirection, cursor: String): SequencePage {
                check(!sequenceClosed)
                return SourceWire.sequence(call(ExtensionProtocol.SEQUENCE_PAGE,JSONObject().put("token",token).put("direction",direction.name).put("cursor",cursor)))
            }
            override fun close() { sequenceClosed = true }
        }
    }
    override fun close() { closed = true }
}
