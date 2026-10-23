package one.njk.sao.importing

import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import one.njk.sao.floating.*
import one.njk.sao.immichprovider.ImmichConfig
import org.junit.Assert.*
import org.junit.Test

class ImportRecoveryDeviceTest {
    @Test fun restoredBatchResumesOnlyUncommittedInputsWithoutReusingOriginalIntent(): Unit=runBlocking {
        NativeImportFixtures().use { fixture ->
            val app=fixture.app; val repository=app.imports()
            val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
            val previous=access.sources().snapshots.first()
            val server=MockWebServer().apply {
                dispatcher=object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest)=if(request.requestUrl!!.encodedPath=="/api/api-keys/me")
                        MockResponse().setBody("""{"id":"fixture","name":"fixture","permissions":["all"]}""")
                    else MockResponse().setResponseCode(404)
                }; start()
            }
            val holder=ViewModelStore()
            val profile=immichProfile("Recovery fixture",ImmichConfig(server.url("/").toString(),"synthetic-key"))
            access.sources().upsert(profile)
            repository.store.pause(profile.id,true) // Preparing is allowed; no network upload should run.
            try {
                val first=fixture.image("already-committed.png",Color.RED).first
                val second=fixture.image("remaining.png",Color.BLUE).first
                val destination=repository.destinations(profile.id).single(); val target=destination.options.targets.single()
                repository.enqueue(destination,target,listOf(first))
                val saved=SavedStateHandle(mapOf("source" to profile.id,"target" to target.id,
                    "incoming" to arrayListOf(second),"received" to false))
                lateinit var model: MediaImportViewModel
                fixture.instrumentation.runOnMainSync {
                    model=MediaImportViewModel(repository,saved); holder.put("imports",model)
                    model.incoming(listOf(first,second)) // A recreated Activity still has the original share intent.
                }
                withTimeout(10_000) { model.state.first { it.received } }
                assertTrue(saved.get<Boolean>("received")==true)
                assertNull(saved.get<ArrayList<Uri>>("incoming"))
                assertEquals(2,repository.store.stats(profile.id).pending)
                repository.store.readableDatabase.rawQuery("SELECT uri FROM tasks WHERE source=? ORDER BY rowid",arrayOf(profile.id)).use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals(first.toString(),rows.getString(0))
                    assertTrue(rows.moveToNext()); assertEquals(second.toString(),rows.getString(0)); assertFalse(rows.moveToNext())
                }
            } finally {
                fixture.instrumentation.runOnMainSync { holder.clear() }
                repository.clear(profile.id)
                access.sources().restore(previous); server.shutdown()
            }
        }
    }
}
