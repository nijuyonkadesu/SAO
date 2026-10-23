package one.njk.sao.importing

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import one.njk.sao.floating.*
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real signed Binder, read-granted snapshot and streaming HTTP multipart; synthetic media/localhost only. */
class ImmichImportDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun aLostUploadReplyDoesNotRetransmitTheFileAndOriginalsAreKept()=runBlocking {
        val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
        val prior=access.sources().snapshots.first()
        val uploads=AtomicInteger(); val checks=AtomicInteger(); val bytes="synthetic-original-media-${UUID.randomUUID()}".toByteArray()
        val server=MockWebServer()
        var uploaded=false
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when(request.requestUrl!!.encodedPath) {
                "/api/api-keys/me" -> MockResponse().setBody("""{"id":"fixture","name":"fixture","permissions":["all"]}""")
                "/api/assets/bulk-upload-check" -> {
                    checks.incrementAndGet()
                    val id=JSONObject(request.body.readUtf8()).getJSONArray("assets").getJSONObject(0).getString("id")
                    MockResponse().setBody(if(uploaded) """{"results":[{"id":"$id","action":"reject","reason":"duplicate","assetId":"asset-fixture"}]}"""
                        else """{"results":[{"id":"$id","action":"accept"}]}""")
                }
                "/api/assets" -> {
                    uploads.incrementAndGet(); uploaded=true
                    assertTrue(request.body.readUtf8().contains(String(bytes)))
                    assertNotNull(request.getHeader("x-immich-checksum"))
                    // The server accepted the bytes but the client receives a transient failure.
                    MockResponse().setResponseCode(503)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"import-${UUID.randomUUID()}.png"); put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
        })!!
        app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        val imports=app.imports()
        var profile: SourceProfile?=null
        try {
            profile=immichProfile("Upload fixture",ImmichConfig(server.url("/").toString(),"fixture-secret"))
            access.sources().upsert(profile)
            val destination=imports.destinations(profile.id).single()
            assertEquals("default",destination.options.targets.single().id)
            assertTrue(destination.options.connectedFolders)
            imports.store.add(profile.id,profile.identity,"default",MediaDocuments.details(app.contentResolver,uri),network=true)
            imports.transfer(false)
            assertEquals(1,uploads.get()); assertEquals(1,imports.store.stats(profile.id).pending)
            val queued=imports.store.readableDatabase.rawQuery("SELECT checksum,staged FROM tasks WHERE source=?",arrayOf(profile.id)).use {
                it.moveToFirst(); it.getString(0) to it.getString(1)
            }
            assertNotNull(queued.first); assertTrue(java.io.File(queued.second).isFile)
            imports.store.retry(profile.id); imports.transfer(false)
            assertEquals(1,uploads.get()); assertEquals(2,checks.get()); assertEquals(1,imports.store.stats(profile.id).done)
            assertFalse(java.io.File(queued.second).exists())
            assertArrayEquals(bytes,app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(imports.scan()) // No connected routes: no source/network work.
            assertEquals(2,checks.get())
            ActivityScenario.launch<MediaImportActivity>(Intent(app,MediaImportActivity::class.java).putExtra("source",profile.id)).use { scenario ->
                var visible=false
                withTimeout(10_000) { while(!visible) {
                    scenario.onActivity { visible=it.findViewById<android.view.View>(one.njk.sao.R.id.add_media).isShown }
                    delay(50)
                } }
                pauseSourceVisual("media-import-source")
                scenario.recreate()
                scenario.onActivity { assertEquals(profile.name,it.findViewById<android.widget.TextView>(one.njk.sao.R.id.title).text.toString()) }
            }
        } finally {
            profile?.let { imports.store.writableDatabase.delete("tasks","source=?",arrayOf(it.id)) }
            access.sources().restore(prior)
            app.contentResolver.delete(uri,null,null)
            server.shutdown()
        }
        Unit
    }
}
