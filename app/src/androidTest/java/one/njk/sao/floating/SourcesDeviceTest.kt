package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Provider
import one.njk.sao.R

class SourcesDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private val profiles get() = access.sources()
    private val dispatchers = FloatingDispatchers()
    private var previous = SourceCatalog()
    private val routers = mutableListOf<SwitchingMediaSource>()
    @Before fun setup() = runBlocking { previous = profiles.current(); profiles.restore(SourceCatalog()); access.browseSession().reset() }
    @After fun cleanup() = runBlocking { routers.forEach { it.shutdown() }; profiles.restore(previous); access.browseSession().reset() }
    private fun profile(name: String, url: String = "http://example.test", key: String = "test-profile-key") =
        SourceProfile(UUID.randomUUID().toString(), name, ExtensionConfig("fixture.source","fixture.source.Service",UUID.randomUUID().toString(),digest("$url|$key"),"Fixture"))
    private fun router(factory: MediaSourceFactory = ExtensionSourceFactory(app,testExtensions(app),SourceMediaCache(app))) =
        SwitchingMediaSource(profiles, mapOf("extension" to Provider { factory }), dispatchers).also { routers += it }

    @Test fun manyNamedConnectionsPersistEncryptedAndDeletingSelectedChoosesRemainingSource(): Unit = runBlocking {
        val rows = (1..40).map { profile("Library $it", key = "opaque-profile-$it") }
        rows.forEach { profiles.upsert(it) }; profiles.select(rows.last().id)
        val fresh = SourceProfiles(app, dispatchers, mapOf("extension" to ExtensionConfigurationCodec()))
        assertEquals(rows.map { it.id }, fresh.current().profiles.map { it.id })
        assertEquals("Library 40", fresh.current().selected!!.name)
        val ciphertext = File(app.noBackupFilesDir, "source-profiles-v1").readBytes().toString(Charsets.UTF_8)
        assertFalse(ciphertext.contains("opaque-profile-")); assertFalse(ciphertext.contains("example.test"))
        profiles.remove(rows.last().id)
        assertEquals(rows.first().id,profiles.current().selectedId); assertEquals(39, profiles.current().profiles.size)
        rows.dropLast(1).forEach { profiles.remove(it.id) }
        assertNull(profiles.current().selected); assertTrue(router().page(MediaQuery()).items.isEmpty())
    }

    @Test fun legacyConnectionMigratesOnceWithMetadataOptOutAndSelectsItsReplacement(): Unit = runBlocking {
        val directory = File(app.cacheDir, "source-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        try {
            EncryptedSettingsFile(context, "immich-connection-v1", "sao-immich-connection-v1").write(
                JSONObject().put("url", "http://legacy.test").put("key", "migration-secret").put("smart", false)
                    .put("album", "album-id").put("enabled", false))
            val migrated = SourceProfiles(context, dispatchers, mapOf("extension" to ExtensionConfigurationCodec())).current()
            assertNull(migrated.selectedId)
            val config = JSONObject((migrated.profiles.single().configuration as UnknownSourceConfiguration).json)
            assertFalse(config.getBoolean("smart")); assertEquals("album-id", config.getString("album")); assertEquals("migration-secret", config.getString("key"))
            assertFalse(File(directory, "immich-connection-v1").exists())
            val again = SourceProfiles(context, dispatchers, mapOf("extension" to ExtensionConfigurationCodec())).current()
            assertEquals(migrated.profiles.single().id, again.profiles.single().id)
            val repository=SourceProfiles(context,dispatchers,mapOf("extension" to ExtensionConfigurationCodec()))
            val extensions=SourceExtensions(context,repository)
            val extension=extensions.installed().single { "immich" in it.legacyKinds }
            extensions.migrate(extension)
            val replacement=repository.current().profiles.single()
            assertTrue(replacement.configuration is ExtensionConfig)
            assertEquals(replacement.id,repository.current().selectedId)
            assertFalse(ExtensionConfigurationCodec().encode(replacement.configuration).toString().contains("migration-secret"))
            val ref=replacement.configuration as ExtensionConfig
            ExtensionClient(app,ref.component).request(ref.profile,ExtensionProtocol.DELETE_PROFILE,JSONObject().put("revision",ref.revision))
        } finally { directory.deleteRecursively() }
    }

    @Test fun factoriesAreLazyRenameKeepsClientAndSwitchCancelsOldWork(): Unit = runBlocking {
        val first = profile("First"); val slow = profile("Slow", "http://slow.test"); val last = profile("Last")
        listOf(first, slow, last).forEach { profiles.upsert(it) }
        var created = 0; var closed = 0
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val factory = object : MediaSourceFactory {
            override fun create(profile: SourceProfile): ManagedMediaSource {
                created++
                return object : ManagedMediaSource {
                    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage {
                        if (profile.id == slow.id) {
                            entered.complete(Unit)
                            try { awaitCancellation() } finally { cancelled.complete(Unit) }
                        }
                        return MediaPage(emptyList())
                    }
                    override suspend fun filters() = emptyList<String>()
                    override val identity = kotlinx.coroutines.flow.flowOf(profile.identity)
                    override suspend fun owns(item: MediaItem) = item.remote?.sourceId == profile.identity
                    override suspend fun preview(item: MediaItem): Preview = error("Unused")
                    override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
                        block: suspend (MediaHandle) -> T): T = error("Unused")
                    override fun close() { closed++ }
                }
            }
        }
        val source = router(factory)
        assertEquals(0, created); profiles.select(first.id); assertEquals(0, created)
        source.filters(); assertEquals(1, created)
        profiles.upsert(first.copy(name = "Renamed")); source.filters(); assertEquals(1, created)
        profiles.select(slow.id)
        val work = launch { source.page(MediaQuery()) }
        withTimeout(3000) { entered.await() }
        profiles.select(last.id)
        withTimeout(3000) { cancelled.await(); work.join() }
        assertTrue(work.isCancelled); assertEquals(2, closed)
        source.filters(); assertEquals(3, created)
        profiles.select(null); source.page(MediaQuery()); assertEquals(3, created); assertEquals(3, closed)
    }

}
