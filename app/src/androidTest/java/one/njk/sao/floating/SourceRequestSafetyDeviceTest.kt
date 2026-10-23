package one.njk.sao.floating

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider

/** Queued and in-flight helper reads must retain the profile that initiated them. */
class SourceRequestSafetyDeviceTest {
    private suspend fun isolated(block: suspend (SourceProfiles, SourceProfile, SourceProfile) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "source-request-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        val profiles = SourceProfiles(context, FloatingDispatchers(), mapOf("extension" to ExtensionConfigurationCodec()))
        fun profile(name: String) = SourceProfile(UUID.randomUUID().toString(), name,
            ExtensionConfig("fixture", "fixture.Service", name, "1", "Fixture"))
        try {
            val a = profile("One"); val b = profile("Two")
            profiles.upsert(a, true); profiles.upsert(b)
            block(profiles, a, b)
        } finally { directory.deleteRecursively() }
    }

    private fun factory(calls: MutableList<String>, people: suspend () -> Unit = {}): MediaSourceFactory =
        object : MediaSourceFactory {
            override fun create(profile: SourceProfile) = object : ManagedMediaSource {
                override val identity = flowOf(profile.identity)
                override suspend fun owns(item: MediaItem) = item.remote?.sourceId == profile.identity
                override suspend fun page(query: MediaQuery, cursor: String?) = MediaPage(emptyList())
                override suspend fun suggestions(query: MediaQuery, cursor: Int): SearchSuggestions {
                    calls += "${profile.id}:suggestions"
                    return SearchSuggestions.empty(cursor)
                }
                override suspend fun people(name: String, page: Int): PeoplePage {
                    calls += "${profile.id}:people"; people()
                    return PeoplePage(emptyList())
                }
                override suspend fun places(scope: PlaceScope): List<String> {
                    calls += "${profile.id}:places"; return emptyList()
                }
                override suspend fun personPreview(person: SearchPerson): Preview? {
                    calls += "${profile.id}:portrait"; return null
                }
                override suspend fun preview(item: MediaItem): Preview = error("Unused")
                override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
                    block: suspend (MediaHandle) -> T): T = error("Unused")
                override fun close() { }
            }
        }

    @Test fun queuedHelpersRejectTheOldOwnerBeforeAnyReplacementRequest(): Unit = runBlocking {
        isolated { profiles, a, b ->
            val calls = CopyOnWriteArrayList<String>()
            val factory = factory(calls)
            val source = SwitchingMediaSource(profiles, mapOf("extension" to Provider { factory }), FloatingDispatchers())
            try {
                val queued = CompletableDeferred<Unit>()
                val request = async {
                    queued.await()
                    source.withSource(a.identity) { it.people("Old query") }
                }
                profiles.select(b.id); queued.complete(Unit)
                request.join(); assertTrue(request.isCancelled)
                for (operation in listOf<suspend (MediaSource) -> Unit>(
                    { it.suggestions(MediaQuery("blue"), 4) },
                    { it.places(PlaceScope(PlaceLevel.COUNTRY)) },
                )) {
                    try { source.withSource(a.identity, operation); fail("Dispatched stale helper") }
                    catch (_: CancellationException) { }
                }
                try { source.personPreview(SearchPerson("person", "Old person", a.identity)); fail("Dispatched stale portrait") }
                catch (_: CancellationException) { }
                assertTrue("No helper may reach the replacement source", calls.isEmpty())
                source.withSource(b.identity) { owned ->
                    owned.suggestions(MediaQuery("blue"), 4)
                    owned.people("New query")
                    owned.places(PlaceScope(PlaceLevel.COUNTRY))
                }
                assertEquals(listOf("${b.id}:suggestions", "${b.id}:people", "${b.id}:places"), calls.toList())
            } finally { source.shutdown() }
        }
    }

    @Test fun switchingCancelsTheCapturedSessionWithoutReroutingItsWork(): Unit = runBlocking {
        isolated { profiles, a, b ->
            val calls = CopyOnWriteArrayList<String>()
            val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val factory = factory(calls) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            val source = SwitchingMediaSource(profiles, mapOf("extension" to Provider { factory }), FloatingDispatchers())
            try {
                val request = async { source.withSource(a.identity) { it.people("Active query") } }
                withTimeout(5000) { started.await() }
                profiles.select(b.id)
                withTimeout(5000) { cancelled.await(); request.join() }
                assertTrue(request.isCancelled)
                assertEquals(listOf("${a.id}:people"), calls.toList())
            } finally { source.shutdown() }
        }
    }
}
