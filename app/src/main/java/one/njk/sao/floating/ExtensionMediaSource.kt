package one.njk.sao.floating

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.njk.sao.source.*
import org.json.JSONObject
import java.io.File

/** Generic adapter. All source-specific API shapes, credentials and URLs stay across the boundary. */
class ExtensionMediaSource(private val context: Context, private val profile: SourceProfile,
    private val extensions: SourceExtensions, private val cache: SourceMediaCache) : ManagedMediaSource {
    private val config = profile.configuration as ExtensionConfig
    private val client = ExtensionClient(context,config.component)
    @Volatile private var closed = false
    private val capabilityLock = Mutex()
    private var discoveryQuery: MediaQuery? = null
    private val capabilityState = MutableStateFlow<SourceCapabilitiesState>(SourceCapabilitiesState.Loading)
    override val identity = flowOf(profile.identity)
    override suspend fun owns(item: MediaItem) = item.remote?.sourceId == profile.identity
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
    override val capabilitiesState = flow {
        try { loadCapabilities() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Failure is retained below; only a later user request retries. */ }
        emitAll(capabilityState)
    }
    override val capabilities = capabilitiesState.map {
        (it as? SourceCapabilitiesState.Ready)?.value ?: SourceCapabilities()
    }.distinctUntilChanged()
    private suspend fun loadCapabilities(): SourceCapabilities = capabilityLock.withLock {
        (capabilityState.value as? SourceCapabilitiesState.Ready)?.value ?: run {
            capabilityState.value = SourceCapabilitiesState.Loading
            try {
                SourceWire.capabilities(call(SourceFeature.CAPABILITIES,ExtensionProtocol.CAPABILITIES))
                    .also { capabilityState.value = SourceCapabilitiesState.Ready(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                capabilityState.value = SourceCapabilitiesState.Failed(error)
                throw error
            }
        }
    }
    private fun ExtensionFailure.asSourceFailure(feature: SourceFeature) =
        if (code == "UNSUPPORTED") SourceUnsupportedException(feature) else SourceReadException(messageResource(),this)
    private suspend fun request(operation: String, value: JSONObject): JSONObject {
        check(!closed)
        extensions.requireInstalled(config)
        return client.request(config.profile,operation,value.put("source",profile.identity).put("revision",config.revision))
    }
    private suspend fun call(feature: SourceFeature, operation: String, value: JSONObject = JSONObject()): JSONObject {
        check(!closed)
        try {
            val result = ReadRetry.run { request(operation,value) }
            check(!closed)
            return result
        } catch (error: ExtensionFailure) { throw error.asSourceFailure(feature) }
    }
    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage {
        val normalized = loadCapabilities().normalize(query)
        val discover=cursor==null && discoveryQuery!=normalized
        var next = cursor
        val seen = mutableSetOf<String>()
        // Empty filtered pages are advanced by the host, serially and cancellably.
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = SourceWire.page(call(SourceFeature.PAGE,ExtensionProtocol.SEARCH,JSONObject().put("query",SourceWire.query(normalized)).put("cursor",next).put("discover",discover)))
            discoveryQuery=normalized
            check(page.items.all { it.remote?.sourceId == profile.identity }) { "Invalid source identity" }
            if (page.items.isNotEmpty() || page.nextCursor == null) return page
            check(page.nextCursor != next && seen.add(requireNotNull(page.nextCursor))) { "Repeated source cursor" }
            next = page.nextCursor
        }
    }
    override suspend fun filters(): List<String> {
        if (SearchFacet.EMOJI_TAGS !in loadCapabilities().facets) throw SourceUnsupportedException(SourceFeature.FILTERS)
        return SourceWire.strings(call(SourceFeature.FILTERS,ExtensionProtocol.FILTERS).optJSONArray("values"))
    }
    override suspend fun suggestions(query: MediaQuery, cursor: Int): SearchSuggestions {
        if(query.mode !in loadCapabilities().suggestionModes) return SearchSuggestions.empty(cursor)
        // Obsolete typeahead is cancelled rather than retried; the next edit starts a fresh request.
        return try { SourceWire.suggestions(request(ExtensionProtocol.SUGGESTIONS,
            JSONObject().put("query",SourceWire.query(query)).put("cursor",cursor))).validate(query.text,cursor) }
        catch(error: ExtensionFailure) { throw error.asSourceFailure(SourceFeature.SUGGESTIONS) }
    }
    override suspend fun people(name: String, page: Int): PeoplePage {
        val reply = call(SourceFeature.PEOPLE,ExtensionProtocol.PEOPLE,JSONObject().put("name",name).put("page",page))
        val rows = reply.getJSONArray("people")
        return PeoplePage(List(rows.length()) { SourceWire.person(rows.getJSONObject(it)) },reply.optInt("next").takeIf { it > 0 })
    }
    override suspend fun places(scope: PlaceScope): List<String> = SourceWire.strings(call(SourceFeature.PLACES,ExtensionProtocol.PLACES,
        JSONObject().put("level",scope.level.name).put("country",scope.country).put("state",scope.state)).optJSONArray("values"))
    private fun validate(uri: String): Uri = Uri.parse(uri).also {
        require(it.scheme == "content" && context.packageManager.resolveContentProvider(it.authority.orEmpty(),0)?.packageName == config.packageName)
    }
    private suspend fun <T> withFile(item: MediaItem, kind: String, block: suspend (File) -> T): T {
        check(item.remote?.sourceId == profile.identity && !closed)
        val keys = requireNotNull(item.remote).cacheKeys
        val variant = keys[kind] ?: kind
        val fullSize = kind != "preview" || variant == (keys["original"] ?: "original")
        val maxBytes = if (kind == "playback" || !fullSize) 8L*1024*1024 else 64L*1024*1024
        return mediaRead(if(kind=="preview") SourceFeature.PREVIEW else SourceFeature.RENDITION) { cache.withFile(profile.identity,"${item.id}:$variant",maxBytes, {
            validate(request(ExtensionProtocol.MEDIA,JSONObject().put("item",SourceWire.item(item)).put("kind",kind)).getString("uri"))
        }, block) }
    }
    private suspend fun <T> mediaRead(feature: SourceFeature, block: suspend () -> T): T = try { block() }
        catch (error: ExtensionFailure) { throw error.asSourceFailure(feature) }
    override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview) -> T): T =
        withFile(item,"preview") { block(Preview.Local(it)) }
    private fun playbackKind(item: MediaItem) = if (item.remote?.cacheKeys?.containsKey("playback") == true) "playback" else "video"
    override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
        block: suspend (MediaHandle) -> T): T = when (kind) {
        Rendition.ORIGINAL -> withFile(item, "original") { file -> block(MediaHandle.Local(file)) }
        Rendition.PLAYBACK -> { require(item.isVideo); withFile(item, playbackKind(item)) { file -> block(MediaHandle.Local(file)) } }
        Rendition.STILL -> withFile(item, "raster") { file -> block(MediaHandle.Local(file)) }
    }
    override suspend fun <T> withPersonPreview(person: SearchPerson, block: suspend (Preview?) -> T): T {
        check(person.sourceId == profile.identity)
        return mediaRead(SourceFeature.PERSON_PREVIEW) { cache.withFile(profile.identity,"person:${person.id}",2L*1024*1024, {
            validate(request(ExtensionProtocol.PERSON_MEDIA,JSONObject().put("person",SourceWire.person(person))).getString("uri"))
        }) { block(Preview.Local(it)) } }
    }
    override suspend fun openSequence(anchor: MediaItem): MediaSequence {
        check(loadCapabilities().sequence)
        val initial = call(SourceFeature.SEQUENCE,ExtensionProtocol.SEQUENCE_OPEN,JSONObject().put("item",SourceWire.item(anchor)))
        val token = initial.getJSONObject("token")
        return object : MediaSequence {
            private var sequenceClosed = false
            override suspend fun initial(): SequencePage { check(!sequenceClosed); return SourceWire.sequence(initial) }
            override suspend fun page(direction: SequenceDirection, cursor: String): SequencePage {
                check(!sequenceClosed)
                return SourceWire.sequence(call(SourceFeature.SEQUENCE,ExtensionProtocol.SEQUENCE_PAGE,JSONObject().put("token",token).put("direction",direction.name).put("cursor",cursor)))
            }
            override fun close() { sequenceClosed = true }
        }
    }
    override fun close() { closed = true }
}
