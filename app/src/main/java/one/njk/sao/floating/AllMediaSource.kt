package one.njk.sao.floating

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Provider
import kotlin.random.Random

/** Synthetic host profile. It contains public profile references, never credentials or media. */
internal data class AllSourceConfiguration(val members: List<SourceProfile>) : SourceConfiguration {
    override val kind = "all"
    override val identity = digest(members.map { it.identity }.sorted().joinToString("|"))
}

internal const val ALL_SOURCE_ID = "a1100000-0000-4000-8000-000000000001"
internal fun SourceCatalog.withAllSource(enabled: Boolean = profiles.any { it.id == ALL_SOURCE_ID },
    available: Set<String>? = null): SourceCatalog {
    val real = profiles.filterNot { it.id == ALL_SOURCE_ID }
    val rows = if (!enabled) real else real + SourceProfile(ALL_SOURCE_ID, "All",
        AllSourceConfiguration(real.filter { available == null || it.id in available }))
    return SourceCatalog(rows, if(selectedId == ALL_SOURCE_ID && !enabled) real.firstOrNull()?.id
        else selectedId?.takeIf { id -> rows.any { it.id == id } })
}

/** Bounded fan-out and stateless, per-query cursors. No previous-query result cache. */
internal class AllMediaSource(private val profile: SourceProfile,
    factories: Map<String, Provider<MediaSourceFactory>>,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers()) : ManagedMediaSource {
    private val members = (profile.configuration as AllSourceConfiguration).members
    private val children = members.associate { member -> member.identity to lazy {
        checkNotNull(factories[member.configuration.kind]) { "Source provider is unavailable" }.get().create(member)
    } }
    private val requests = Semaphore(3)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    @Volatile private var closed = false
    private fun child(identity: String): ManagedMediaSource {
        check(!closed)
        val source = checkNotNull(children[identity]) { "Media belongs to a different source" }.value
        if(closed) { source.close(); error("Source closed") }
        return source
    }
    private fun child(item: MediaItem) = child(requireNotNull(item.remote).sourceId)
    override val identity = flowOf(profile.identity)
    override suspend fun owns(item: MediaItem) = !closed && item.remote?.sourceId in children
    private fun memberCapabilities(member: SourceProfile): Flow<SourceCapabilitiesState> = flow {
        requests.acquire()
        var holding = true
        try {
            child(member.identity).capabilitiesState.collect { value ->
                if(holding && value != SourceCapabilitiesState.Loading) { holding=false; requests.release() }
                emit(value)
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { emit(SourceCapabilitiesState.Failed(error)) }
        finally { if(holding) requests.release() }
    }
    private val memberStates = members.map { member -> memberCapabilities(member)
        .stateIn(scope, SharingStarted.WhileSubscribed(0), SourceCapabilitiesState.Loading) }
    override val capabilitiesState: Flow<SourceCapabilitiesState> = if(members.isEmpty()) flowOf(SourceCapabilitiesState.Ready(SourceCapabilities())) else
        combine(memberStates) { statuses ->
            if(statuses.any { it == SourceCapabilitiesState.Loading }) return@combine SourceCapabilitiesState.Loading
            val supported = statuses.filterIsInstance<SourceCapabilitiesState.Ready>().map { it.value }
            val available = commonCapabilities(supported)
            statuses.filterIsInstance<SourceCapabilitiesState.Failed>().firstOrNull()?.let {
                SourceCapabilitiesState.Failed(it.error, available)
            } ?: SourceCapabilitiesState.Ready(available)
        }.stateIn(scope, SharingStarted.WhileSubscribed(0), SourceCapabilitiesState.Loading)
    override val capabilities = capabilitiesState.filterNot { it == SourceCapabilitiesState.Loading }
        .map { it.available }.distinctUntilChanged()
    private fun commonCapabilities(supported: List<SourceCapabilities>): SourceCapabilities {
        if(supported.isEmpty()) return SourceCapabilities()
        val modes = supported.map { it.modes }.reduce { a, b -> a intersect b }
        val facets = supported.map { it.facets }.reduce { a, b -> a intersect b } -
            setOf(SearchFacet.PEOPLE, SearchFacet.PLACES)
        return SourceCapabilities(modes, facets, supported.all { it.random }, supported.all { it.sequence },
            defaultMode = listOf(SearchMode.CONTEXT, SearchMode.DESCRIPTION).firstOrNull { it in modes } ?: modes.firstOrNull() ?: SearchMode.CONTEXT,
            categories = supported.flatMap { it.categories }.distinctBy { it.id })
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override val changes = merge(*members.mapIndexed { index, member ->
        // Initial capability IPC shares the page budget; observing a URI never holds a permit.
        memberStates[index].flatMapLatest { status ->
            val caps = status.available
            if(caps.changesUri == null) emptyFlow() else flow {
                try { emitAll(child(member.identity).changes) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { /* One failed observer cannot stop the other libraries. */ }
            }
        }
    }.toTypedArray())
    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage = withContext(dispatchers.cpu) {
        check(!closed)
        val signature = digest(profile.identity + SourceWire.query(query).toString())
        val state = cursor?.let(::JSONObject) ?: JSONObject().put("key", signature)
            .put("seed", Random.nextLong()).put("turn", 0).put("positions", JSONObject())
        require(state.getString("key") == signature) { "Results belong to a different query or source" }
        val positions = state.getJSONObject("positions")
        val order = members.shuffled(Random(state.getLong("seed")))
        val turn = state.getInt("turn")
        val pending = order.filter { positions.optJSONObject(it.identity)?.optBoolean("done") != true }
        if (pending.isEmpty()) return@withContext MediaPage(emptyList())
        // Rotate fairly through arbitrarily many profiles, reading at most three pages per request.
        val selected = (pending.drop(turn % pending.size) + pending.take(turn % pending.size)).take(3)
        val pages = selected.map { member -> async {
            requests.withPermit {
                try {
                    val position = positions.optJSONObject(member.identity)
                    val next = position?.let { SourceWire.nullable(it, "cursor") }
                    val page = child(member.identity).page(query.copy(constraints = query.constraints.copy(
                        sourceId = null, people = emptyList(), country = null, state = null, city = null)), next)
                    val seen = SourceWire.strings(position?.optJSONArray("seen"))
                    page.nextCursor?.let { continuation ->
                        check(continuation != next && digest(continuation) !in seen) { "Repeated source cursor" }
                    }
                    member to Result.success(page)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { member to Result.failure<MediaPage>(error) }
            }
        } }.awaitAll()
        val items = mutableListOf<MediaItem>()
        var interrupted = false
        pages.forEach { (member, result) ->
            val page = result.getOrNull()
            if (page == null) interrupted = true else {
                items += page.items
                val seen = SourceWire.strings(positions.optJSONObject(member.identity)?.optJSONArray("seen"))
                positions.put(member.identity, JSONObject().put("cursor", page.nextCursor)
                    .put("seen",JSONArray(seen + listOfNotNull(page.nextCursor?.let(::digest))))
                    .put("done", page.nextCursor == null))
                interrupted = interrupted || page.interrupted
            }
        }
        state.put("turn", turn + selected.size)
        val more = members.any { positions.optJSONObject(it.identity)?.optBoolean("done") != true }
        MediaPage(items.distinctBy { it.identityKey }.shuffled(Random(state.getLong("seed") xor turn.toLong())),
            state.toString().takeIf { more }, interrupted)
    }
    override suspend fun filters(): List<String> {
        if (SearchFacet.EMOJI_TAGS !in capabilities.first().facets) throw SourceUnsupportedException(SourceFeature.FILTERS)
        return members.flatMap { member -> child(member.identity).filters() }.distinct()
    }
    override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview) -> T): T =
        child(item).withPreview(item,block)
    override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
        block: suspend (MediaHandle) -> T): T = child(item).withRendition(item, kind, block)
    override suspend fun <T> withPlayback(item: MediaItem, original: Boolean, block: suspend (MediaPlayback) -> T): T =
        child(item).withPlayback(item, original, block)
    override suspend fun openSequence(anchor: MediaItem) = child(anchor).openSequence(anchor)
    override fun close() {
        closed = true
        scope.cancel()
        children.values.filter { it.isInitialized() }.forEach { it.value.close() }
    }
}
