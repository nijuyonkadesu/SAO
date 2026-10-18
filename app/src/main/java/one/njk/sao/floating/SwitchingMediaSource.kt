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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** One active provider session. Factory lookup is lazy and configurations remain plain values. */
@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SwitchingMediaSource @Inject constructor(private val profiles: SourceProfiles,
    private val factories: Map<String, @JvmSuppressWildcards Provider<MediaSourceFactory>>,
    private val dispatchers: FloatingDispatchers) : MediaSource {
    private class Session(val identity: String, val source: MediaSource) {
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
    override val identity = profiles.catalogs.map { it.identity }
        .catch { error -> if (error is CancellationException) throw error; emit("unavailable") }.distinctUntilChanged()
    override val changes = profiles.catalogs.map { it.identity }.distinctUntilChanged()
        .flatMapLatest { flow { emitAll(session().source.changes) }.catch { error -> if(error is CancellationException) throw error } }
    override val capabilities = profiles.catalogs.map { it.identity }.distinctUntilChanged()
        .flatMapLatest { id -> flow {
            emitAll(session().source.capabilities.map { it.copy(sourceId = id) })
        }.catch { error -> if (error is CancellationException) throw error; emit(SourceCapabilities(sourceId = id)) } }
        .stateIn(scope, SharingStarted.WhileSubscribed(0),SourceCapabilities())
    override val advancedSearch = capabilities.map { it.searchOptions }.distinctUntilChanged()
    override val randomBrowse = capabilities.map { it.random }.distinctUntilChanged()
    override val sequenceBrowse = capabilities.map { it.sequence }.distinctUntilChanged()

    private suspend fun session(): Session = withContext(dispatchers.io) { mutex.withLock {
        if (!watching) {
            watching = true
            scope.launch {
                try { profiles.catalogs.map { it.identity }.distinctUntilChanged().collect { _ ->
                    mutex.withLock { if (active?.identity != profiles.current().identity) { active?.close(); active = null } }
                } } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutex.withLock { active?.close(); active = null; watching = false }
                }
            }
        }
        val catalog = profiles.current()
        active?.takeIf { it.identity == catalog.identity } ?: run {
            active?.close(); active = null
            val profile = catalog.selected
            val source = if (profile == null) NoSource else if (profile.configuration is AllSourceConfiguration) AllMediaSource(profile, factories, dispatchers) else checkNotNull(factories[profile.configuration.kind]) {
                "Source provider is unavailable"
            }.get().create(profile)
            Session(catalog.identity, source).also { active = it }
        }
    } }
    private suspend fun belongs(session: Session, item: MediaItem) =
        if(session.source is AllMediaSource) session.source.owns(item) else (item.remote?.sourceId ?: "local") == session.identity
    override suspend fun owns(item: MediaItem): Boolean {
        val session = session()
        return belongs(session, item) && profiles.current().identity == session.identity
    }
    private suspend fun <T> read(item: MediaItem? = null, expectedSourceId: String? = null, block: suspend (MediaSource) -> T): T {
        val session = session()
        if (expectedSourceId != null) check(expectedSourceId == session.identity) { "Filter belongs to a different source" }
        if (item != null) check(belongs(session, item)) { "Media belongs to a different source" }
        val result = session.read(block)
        if (profiles.current().identity != session.identity) throw CancellationException("Source changed")
        return result
    }
    internal suspend fun shutdown() = mutex.withLock { scope.cancel(); active?.close(); active = null }
    override suspend fun page(query: MediaQuery, cursor: String?) = read { it.page(query, cursor) }
    override suspend fun openSequence(anchor: MediaItem): MediaSequence {
        val owner = session()
        check(belongs(owner, anchor)) { "Media belongs to a different source" }
        val sequence = owner.read { it.openSequence(anchor) }
        if (profiles.current().identity != owner.identity) {
            sequence.close(); throw CancellationException("Source changed")
        }
        return object : MediaSequence {
            private suspend fun readPage(block: suspend () -> SequencePage): SequencePage {
                check(profiles.current().identity == owner.identity) { "Sequence belongs to a different source" }
                val page = owner.read { block() }
                if (profiles.current().identity != owner.identity) throw CancellationException("Source changed")
                return page
            }
            override suspend fun initial() = readPage { sequence.initial() }
            override suspend fun page(direction: SequenceDirection, cursor: String) = readPage { sequence.page(direction, cursor) }
            override fun close() = sequence.close()
        }
    }
    override suspend fun filters() = read { it.filters() }
    override suspend fun searchModes() = read { it.searchModes() }
    override suspend fun people(name: String, page: Int, expectedSourceId: String?) = read(expectedSourceId = expectedSourceId) { it.people(name, page, expectedSourceId) }
    override suspend fun personPreview(person: SearchPerson) = read(expectedSourceId = person.sourceId) { it.personPreview(person) }
    override suspend fun places(level: PlaceLevel, country: String?, state: String?, expectedSourceId: String?) = read(expectedSourceId = expectedSourceId) { it.places(level, country, state, expectedSourceId) }
    override suspend fun preview(item: MediaItem): Any = read(item) { it.preview(item) }
    override suspend fun video(item: MediaItem): File? = read(item) { it.video(item) }
    override suspend fun original(item: MediaItem): InputStream = read(item) { it.original(item) }
    override suspend fun raster(item: MediaItem): InputStream = read(item) { it.raster(item) }
}
