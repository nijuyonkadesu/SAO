package one.njk.sao.importing

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FolderImportDeviceTest {
    @Test fun nativeFolderMappingFindsOldDatedNewFilesAndDoesNotReuploadUnchangedFiles()=runBlocking {
        NativeImportFixtures().use { fixture ->
            val app=fixture.app; val repository=app.imports()
            val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
            val previous=access.sources().snapshots.first()
            val uploaded=ConcurrentHashMap<String,String>(); val uploads=AtomicInteger(); val checks=AtomicInteger()
            val server=MockWebServer().apply { dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when(request.requestUrl!!.encodedPath) {
                    "/api/api-keys/me" -> MockResponse().setBody("""{"id":"fixture","name":"fixture","permissions":["all"]}""")
                    "/api/assets/bulk-upload-check" -> {
                        checks.incrementAndGet()
                        val row=JSONObject(request.body.readUtf8()).getJSONArray("assets").getJSONObject(0)
                        val id=row.getString("id"); val found=uploaded[row.getString("checksum")]
                        MockResponse().setBody(if(found==null) """{"results":[{"id":"$id","action":"accept"}]}""" else
                            """{"results":[{"id":"$id","action":"reject","reason":"duplicate","assetId":"$found"}]}""")
                    }
                    "/api/assets" -> {
                        val id="asset-${uploads.incrementAndGet()}"; uploaded[request.getHeader("x-immich-checksum")!!]=id
                        MockResponse().setBody("""{"id":"$id","status":"created"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }; start() }
            val profile=immichProfile("Folder upload fixture",ImmichConfig(server.url("/").toString(),"fixture-secret"))
            access.sources().upsert(profile)
            val first=fixture.image("first.png",Color.RED)
            try {
                ActivityScenario.launch<MediaImportActivity>(Intent(app,MediaImportActivity::class.java).putExtra("source",profile.id)).use { scenario ->
                    var ready=false
                    withTimeout(12_000) { while(!ready) { scenario.onActivity { ready=it.findViewById<android.view.View>(one.njk.sao.R.id.connect_folder).isShown }; delay(50) } }
                    scenario.onActivity { it.findViewById<android.view.View>(one.njk.sao.R.id.connect_folder).performClick() }
                    fixture.chooseFolder()
                    withTimeout(15_000) { while(repository.store.routes(profile.id).isEmpty()) delay(50) }
                    repository.scan(); repository.transfer(true)
                    assertEquals("${repository.store.stats(profile.id)}; ${repository.store.routes(profile.id)}",1,repository.store.stats(profile.id).done)
                    val second=fixture.image("ancient.png",Color.BLUE)
                    val downloads=android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    assertTrue(java.io.File(downloads,"${fixture.folder}/ancient.png").setLastModified(946684800000))
                    repository.store.rescan(profile.id); repository.scan(); repository.transfer(true)
                    assertEquals(2,repository.store.stats(profile.id).done); assertEquals(2,uploads.get())
                    val priorChecks=checks.get()
                    repository.store.rescan(profile.id); repository.scan(); repository.transfer(true)
                    assertEquals(2,uploads.get()); assertEquals(priorChecks,checks.get())
                    for((uri,bytes) in listOf(first,second)) assertArrayEquals(bytes,app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                    // Real ACTION_SEND_MULTIPLE and Android's immediate user-initiated transfer job.
                    // Limit discovery to synthetic profiles; never contact any other configured server.
                    val shareSnapshot=access.sources().snapshots.first()
                    try {
                        access.sources().current().profiles.filter { it.id!=profile.id }.forEach { access.sources().remove(it.id) }
                        val share=Intent(app,MediaImportActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(first.first,second.first))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ActivityScenario.launch<MediaImportActivity>(share).use {
                            fixture.click(profile.name)
                            withTimeout(20_000) { while(repository.store.stats(profile.id).done<4) delay(100) }
                            assertEquals(2,uploads.get()) // Same bytes via different URIs deduplicate at Immich.
                        }
                    } finally { access.sources().restore(shareSnapshot) }
                    assertEquals(4,repository.store.stats(profile.id).done)
                    assertEquals(1,repository.store.routes(profile.id).size)
                    ActivityScenario.launch<MediaImportActivity>(Intent(app,MediaImportActivity::class.java).putExtra("source",profile.id)).use {
                        delay(500); pauseSourceVisual("media-import-folder")
                    }
                }
            } finally {
                repository.store.routes(profile.id).forEach { repository.remove(it) }
                repository.store.writableDatabase.delete("tasks","source=?",arrayOf(profile.id))
                access.sources().restore(previous); server.shutdown()
            }
        }
    }
    @Test fun localSourceCopiesWithoutOverwriteAndRepeatedRequestsReturnTheSameReceipt()=runBlocking {
        NativeImportFixtures().use { fixture ->
            val app=fixture.app; val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
            val previous=access.sources().snapshots.first()
            val extension=testExtensions(app).installed().single { it.component.packageName=="one.njk.sao.extension.local" }
            val name="Local import ${java.util.UUID.randomUUID().toString().take(8)}"
            val original=fixture.image("12345.png",Color.GREEN)
            var profile: SourceProfile?=null
            try {
                ExtensionForm(installed=extension).use { form ->
                    form.set("source_name",name); form.click("import_method"); fixture.click("Folder picker")
                    form.click("add_folder"); fixture.chooseFolder(); form.click("save_library")
                    withTimeout(12_000) { while(profile==null) { profile=access.sources().current().profiles.firstOrNull { it.name==name }; delay(50) } }
                }
                val selected=profile!!; val repository=app.imports()
                val destination=repository.destinations(selected.id).single()
                val target=destination.options.targets.single()
                assertFalse(destination.options.network); assertFalse(destination.options.connectedFolders)
                assertTrue(destination.options.collections.isNotEmpty())
                repository.store.add(selected.id,selected.identity,target.id,MediaDocuments.details(app.contentResolver,original.first).copy(name="12345"))
                repository.transfer(false,network=false)
                assertEquals(1,repository.store.stats(selected.id).done)
                assertArrayEquals(original.second,app.contentResolver.openInputStream(original.first)!!.use { it.readBytes() })
                val config=selected.configuration as ExtensionConfig
                val media=ExtensionMediaSource(app,selected,testExtensions(app),SourceMediaCache(app))
                try {
                    var results=media.page(MediaQuery(mode=SearchMode.FILENAME))
                    withTimeout(20_000) { while(results.items.size<2) { delay(100); results=media.page(MediaQuery(mode=SearchMode.FILENAME)) } }
                    assertEquals(2,results.items.size)
                    val copied=results.items.single { it.filename!="12345.png" }
                    assertTrue(copied.filename!!.startsWith("12345 (") && copied.filename!!.endsWith(".png"))
                    assertArrayEquals(original.second,media.original(copied).use { it.readBytes() })
                    repository.store.writableDatabase.execSQL("UPDATE tasks SET status='pending' WHERE source=?",arrayOf(selected.id))
                    repository.transfer(false,network=false)
                    assertEquals(2,media.page(MediaQuery(mode=SearchMode.FILENAME)).items.size)
                } finally { media.close() }
                ActivityScenario.launch<MediaImportActivity>(Intent(app,MediaImportActivity::class.java).putExtra("source",selected.id)).use {
                    delay(500); pauseSourceVisual("media-import-local")
                }
            } finally {
                profile?.let { value ->
                    val config=value.configuration as ExtensionConfig
                    ExtensionClient(app,extension.component).request(config.profile,ExtensionProtocol.DELETE_PROFILE,JSONObject().put("revision",config.revision))
                    app.imports().store.writableDatabase.delete("tasks","source=?",arrayOf(value.id))
                }
                access.sources().restore(previous)
            }
        }
    }
}
