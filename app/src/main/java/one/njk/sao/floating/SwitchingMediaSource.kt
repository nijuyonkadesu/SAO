package one.njk.sao.floating

import one.njk.sao.source.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Provider

/** One active provider session. Factory lookup is lazy and configurations remain plain values. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SwitchingMediaSource(private val profiles: SourceProfiles,
    private val factories: Map<String, @JvmSuppressWildcards Provider<MediaSourceFactory>>,
    private val dispatchers: FloatingDispatchers, private val floatingOnly: Boolean = false) : MediaSource {
    private val catalogs = if (floatingOnly) profiles.floatingCatalogs else profiles.browsingCatalogs
    private suspend fun current() = if (floatingOnly) profiles.currentFloating() else profiles.currentBrowsing()
    private class Session(val identity: String, val profileId: String?, val source: MediaSource) {
        val lifetime = SupervisorJob()
        fun close() { lifetime.cancel(); (source as? ManagedMediaSource)?.close() }
        suspend fun <T> read(block: suspend (MediaSource) -> T): T = coroutineScope {
            val work = async { block(source) }
            val cancellation = lifetime.invokeOnCompletion { work.cancel(CancellationException("Source changed")) }
            try { work.await() } finally { cancellation.dispose() }
        }
    }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var watching = false
    private var active: Session? = null
    override val identity = catalogs.map { it.identity }
        .catch { error -> if (error is CancellationException) throw error; emit("unavailable") }.distinctUntilChanged()
    override val changes = catalogs.map { it.identity }.distinctUntilChanged()
        .flatMapLatest { flow { emitAll(session().source.changes) }.catch { error -> if(error is CancellationException) throw error } }
    override val capabilitiesState = catalogs.map { it.identity }.distinctUntilChanged()
        .flatMapLatest { id -> flow {
            emit(SourceCapabilitiesState.Loading)
            val owner = session()
            if(owner.identity != id) return@flow
            val declared = owner.source.capabilitiesState
            val effective = if(floatingOnly) declared.map { it.scoped(id) } else kotlinx.coroutines.flow.combine(declared,profiles.snapshots) { status,snapshot ->
                status.scoped(id,snapshot.homeCategories[owner.profileId])
            }
            emitAll(effective)
        }.catch { error ->
            if(error is CancellationException || error !is Exception) throw error
            emit(SourceCapabilitiesState.Failed(error, SourceCapabilities(sourceId=id)))
        } }.stateIn(scope, SharingStarted.WhileSubscribed(0),SourceCapabilitiesState.Loading)
    override val capabilities = capabilitiesState.filterNot { it == SourceCapabilitiesState.Loading }
        .map { it.available }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(0),SourceCapabilities())

    private fun SourceCapabilitiesState.scoped(id: String, categories: List<SourceCategory>?=null): SourceCapabilitiesState {
        fun SourceCapabilities.owned() = copy(sourceId=id,categories=categories ?: this.categories)
        return when(this) {
            SourceCapabilitiesState.Loading -> this
            is SourceCapabilitiesState.Ready -> SourceCapabilitiesState.Ready(value.owned())
            is SourceCapabilitiesState.Failed -> copy(available=available.owned())
        }
    }

    private suspend fun session(): Session = withContext(dispatchers.io) { mutex.withLock {
        if (!watching) {
            watching = true
            scope.launch {
                try { catalogs.map { it.identity }.distinctUntilChanged().collect { _ ->
                    mutex.withLock { if (active?.identity != current().identity) { active?.close(); active = null } }
                } } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutex.withLock { active?.close(); active = null; watching = false }
                }
            }
        }
        val catalog = current()
        active?.takeIf { it.identity == catalog.identity } ?: run {
            active?.close(); active = null
            val profile = catalog.selected
            val source = if (profile == null) NoSource else if (profile.configuration is AllSourceConfiguration) AllMediaSource(profile, factories, dispatchers) else checkNotNull(factories[profile.configuration.kind]) {
                "Source provider is unavailable"
            }.get().create(profile)
            Session(catalog.identity, catalog.selectedId, source).also { active = it }
        }
    } }
    private suspend fun belongs(session: Session, item: MediaItem) =
        if(session.source is AllMediaSource) session.source.owns(item) else (item.remote?.sourceId ?: LOCAL_SOURCE_ID) == session.identity
    override suspend fun owns(item: MediaItem): Boolean {
        val session = session()
        return belongs(session, item) && current().identity == session.identity
    }
    private suspend fun <T> read(item: MediaItem? = null, expectedIdentity: String? = null, block: suspend (MediaSource) -> T): T {
        val session = session()
        if (expectedIdentity != null && session.identity != expectedIdentity) throw CancellationException("Source changed")
        if (item != null) check(belongs(session, item)) { "Media belongs to a different source" }
        val result = session.read(block)
        if (current().identity != session.identity) throw CancellationException("Source changed")
        return result
    }
    override suspend fun <T> withSource(expectedIdentity: String, block: suspend (MediaSource) -> T): T =
        read(expectedIdentity=expectedIdentity,block=block)
    internal suspend fun shutdown() = mutex.withLock { scope.cancel(); active?.close(); active = null }
    override suspend fun page(query: MediaQuery, cursor: String?) = read { it.page(query, cursor) }
    override suspend fun openSequence(anchor: MediaItem): MediaSequence {
        val owner = session()
        check(belongs(owner, anchor)) { "Media belongs to a different source" }
        val sequence = owner.read { it.openSequence(anchor) }
        if (current().identity != owner.identity) {
            sequence.close(); throw CancellationException("Source changed")
        }
        return object : MediaSequence {
            private suspend fun readPage(block: suspend () -> SequencePage): SequencePage {
                check(current().identity == owner.identity) { "Sequence belongs to a different source" }
                val page = owner.read { block() }
                if (current().identity != owner.identity) throw CancellationException("Source changed")
                return page
            }
            override suspend fun initial() = readPage { sequence.initial() }
            override suspend fun page(direction: SequenceDirection, cursor: String) = readPage { sequence.page(direction, cursor) }
            override fun close() = sequence.close()
        }
    }
    override suspend fun filters() = read { it.filters() }
    override suspend fun suggestions(query: MediaQuery, cursor: Int) = read { it.suggestions(query,cursor) }
    override suspend fun people(name: String, page: Int) = read { it.people(name, page) }
    override suspend fun <T> withPersonPreview(person: SearchPerson, block: suspend (Preview?) -> T): T =
        read(expectedIdentity=person.sourceId) { it.withPersonPreview(person,block) }
    override suspend fun places(scope: PlaceScope) = read { it.places(scope) }
    override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview) -> T): T =
        read(item) { it.withPreview(item,block) }
    override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
        block: suspend (MediaHandle) -> T): T = read(item) { it.withRendition(item, kind, block) }
    override suspend fun <T> withPlayback(item: MediaItem, original: Boolean, block: suspend (MediaPlayback) -> T): T =
        read(item) { it.withPlayback(item, original, block) }
}
