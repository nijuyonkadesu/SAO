package one.njk.sao.floating

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.inject.Provider

class IndependentSourcesDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile(name: String) = SourceProfile(UUID.randomUUID().toString(),name,
        ExtensionConfig("fixture.source","fixture.source.Service",name,"1","Fixture"))
    private suspend fun isolated(block: suspend (SourceProfiles,ContextWrapper) -> Unit) {
        val directory = File(app.cacheDir,"independent-sources-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        try { block(SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec())),context) }
        finally { directory.deleteRecursively() }
    }
    @Test fun selectionsUseSeparateKeysInTheSameStoreAndSurviveReload(): Unit = runBlocking {
        isolated { profiles,context ->
            val a=profile("First"); val b=profile("Second"); val c=profile("Third")
            listOf(a,b,c).forEach { profiles.upsert(it) }
            profiles.select(b.id)
            assertEquals(a.id,profiles.currentFloating().selectedId)
            profiles.selectFloating(c.id)
            assertEquals(b.id,profiles.current().selectedId)
            val bytes=File(context.noBackupFilesDir,"source-profiles-v1").readBytes()
            profiles.selectFloating(c.id); assertArrayEquals(bytes,File(context.noBackupFilesDir,"source-profiles-v1").readBytes())
            val fresh=SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
            assertEquals(b.id,fresh.current().selectedId); assertEquals(c.id,fresh.currentFloating().selectedId)
            fresh.enableInFloating(c.id,false); assertEquals(a.id,fresh.currentFloating().selectedId)
            fresh.select(a.id); fresh.enableInFloating(c.id,true)
            assertEquals(c.id,fresh.currentFloating().selectedId)
            fresh.available(setOf(a.id,b.id)); assertEquals(a.id,fresh.currentFloating().selectedId)
            fresh.available(setOf(a.id,b.id,c.id)); assertEquals(c.id,fresh.currentFloating().selectedId)
            fresh.remove(c.id); assertEquals(a.id,fresh.currentFloating().selectedId)
            assertEquals(a.id,fresh.current().selectedId)
        }
    }
    @Test fun oldStoreSeedsTheFloatingChoiceOnceThenDecouples(): Unit = runBlocking {
        isolated { profiles,context ->
            val a=profile("First"); val b=profile("Second")
            profiles.upsert(a); profiles.upsert(b); profiles.select(b.id)
            val file=EncryptedSettingsFile(context,"source-profiles-v1","sao-source-profiles-v1")
            file.write(checkNotNull(file.read()).apply { remove("floatingSelected") })
            val migrated=SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
            assertEquals(b.id,migrated.currentFloating().selectedId)
            migrated.select(a.id)
            assertEquals(b.id,migrated.currentFloating().selectedId)
            val stored=checkNotNull(file.read())
            assertEquals(a.id,stored.getString("selected")); assertEquals(b.id,stored.getString("floatingSelected"))
        }
    }
    @Test fun eitherSourceSwitchCancelsOnlyItsOwnInFlightRead(): Unit = runBlocking {
        isolated { profiles,_ ->
            val a=profile("First"); val b=profile("Second")
            profiles.upsert(a); profiles.upsert(b)
            val entered=ChannelGate()
            val factory=object : MediaSourceFactory {
                override fun create(profile: SourceProfile)=object : ManagedMediaSource {
                    override suspend fun page(query: MediaQuery,cursor: String?): MediaPage {
                        if(query.text=="slow") { entered.started.send(Unit); awaitCancellation() }
                        return MediaPage(emptyList())
                    }
                    override suspend fun filters()=emptyList<String>()
                    override val identity=kotlinx.coroutines.flow.flowOf(profile.identity)
                    override suspend fun owns(item: MediaItem)=item.remote?.sourceId==profile.identity
                    override suspend fun preview(item: MediaItem): Preview=error("Unused")
                    override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
                        block: suspend (MediaHandle)->T): T=error("Unused")
                    override fun close() { }
                }
            }
            val factories=mapOf("extension" to Provider<MediaSourceFactory> { factory })
            val main=SwitchingMediaSource(profiles,factories,FloatingDispatchers())
            val floating=SwitchingMediaSource(profiles,factories,FloatingDispatchers(),floatingOnly=true)
            try { supervisorScope {
                val mainRead=async { main.page(MediaQuery("slow")) }; val floatingRead=async { floating.page(MediaQuery("slow")) }
                withTimeout(5000) { repeat(2) { entered.started.receive() } }
                profiles.select(b.id)
                withTimeout(5000) { mainRead.join() }; assertTrue(mainRead.isCancelled); assertTrue(floatingRead.isActive)
                val nextMain=async { main.page(MediaQuery("slow")) }
                withTimeout(5000) { entered.started.receive() }
                profiles.selectFloating(b.id)
                withTimeout(5000) { floatingRead.join() }; assertTrue(floatingRead.isCancelled); assertTrue(nextMain.isActive)
                nextMain.cancelAndJoin()
            } } finally { main.shutdown(); floating.shutdown() }
        }
    }
    private class ChannelGate { val started=kotlinx.coroutines.channels.Channel<Unit>(2) }
}
