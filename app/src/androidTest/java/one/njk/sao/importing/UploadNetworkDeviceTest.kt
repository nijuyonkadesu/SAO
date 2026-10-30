package one.njk.sao.importing

import android.app.job.JobScheduler
import android.content.Intent
import android.graphics.Color
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import one.njk.sao.R
import one.njk.sao.database.SettingsDataStore
import one.njk.sao.floating.*
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic media/localhost only. Radio changes require an explicit emulator test argument. */
class UploadNetworkDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val settings=SettingsDataStore(app)
    private suspend fun await(label: String,condition: ()->Boolean) {
        try { withTimeout(40_000) { while(!condition()) delay(50) } }
        catch(error: TimeoutCancellationException) { throw AssertionError(label,error) }
    }
    @Test fun wifiMeansWifiRatherThanAnyUnmeteredNetworkAndLocalCopiesHaveNoNetworkConstraint() {
        val wifi=UploadNetworkPolicy.Connection(internet=true,wifi=true,cellular=false,unmetered=true)
        val meteredWifi=wifi.copy(unmetered=false)
        val mobile=wifi.copy(wifi=false,cellular=true)
        assertTrue(UploadNetworkPolicy.canUse(wifi,true,true))
        assertTrue(UploadNetworkPolicy.canUse(meteredWifi,true,false))
        assertFalse(UploadNetworkPolicy.canUse(meteredWifi,true,true))
        assertFalse(UploadNetworkPolicy.canUse(mobile,true,false))
        assertTrue(UploadNetworkPolicy.canUse(mobile,false,false))
        assertFalse(UploadNetworkPolicy.canUse(null,true,false))
        assertFalse(UploadNetworkPolicy.canUse(wifi.copy(wifi=false),true,false))
        assertFalse(UploadNetworkPolicy.canUse(wifi.copy(cellular=true),true,false))
        assertEquals(NetworkType.NOT_REQUIRED,ImportJobs.constraints(network=false,wifiOnly=true).requiredNetworkType)
        assertNull(ImportJobs.constraints(network=false,wifiOnly=true).requiredNetworkRequest)
        val automatic=ImportJobs.constraints(network=true,automatic=true,wifiOnly=true).requiredNetworkRequest!!
        assertTrue(automatic.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(automatic.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
    @Test fun settingsPersistAndReplaceExistingUploadConstraintsWithoutChangingReceipts()=runBlocking {
        val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
        val before=access.sources().snapshots.first(); val old=settings.uploadsWifiOnlyFlow().first()
        val repository=app.imports(); val work=WorkManager.getInstance(app)
        val profile=immichProfile("Wi-Fi upload test",ImmichConfig("http://127.0.0.1:9","synthetic-key"))
        try {
            access.sources().upsert(profile)
            repository.store.add(profile.id,profile.identity,"default",MediaDocuments.Entry(Uri.parse("content://fixture/pending.png"),"pending.png","image/png",10,1),network=true)
            repository.store.writableDatabase.execSQL("UPDATE tasks SET next=? WHERE source=?",arrayOf<Any>(System.currentTimeMillis()+3_600_000,profile.id))
            repository.uploadsWifiOnly(true)
            suspend fun constraints(wifi: Boolean) { await("Queued uploads adopt the current network policy") {
                listOf(false,true).all { automatic ->
                    work.getWorkInfosForUniqueWork("media-import-transfer-$automatic-true").get()
                        .filterNot { it.state.isFinished }.singleOrNull()?.constraints?.requiredNetworkRequest
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==wifi
                }
            } }
            constraints(true)
            ActivityScenario.launch<SourceSettingsActivity>(Intent(app,SourceSettingsActivity::class.java)).use { scenario ->
                suspend fun checked(value: Boolean) { await("Upload setting is visible and persistent") {
                    var ready=false
                    scenario.onActivity { activity -> ready=activity.findViewById<MaterialSwitch>(R.id.uploads_wifi_only)?.let { it.isEnabled && it.isChecked==value }==true }
                    ready
                } }
                checked(true); scenario.recreate(); checked(true)
                pauseSourceVisual("wifi-upload-settings")
                scenario.onActivity { it.findViewById<MaterialSwitch>(R.id.uploads_wifi_only).performClick() }
                checked(false); constraints(false)
                assertFalse(settings.uploadsWifiOnlyFlow().first())
            }
            val task=repository.store.next(false,now=Long.MAX_VALUE)!!
            assertEquals(0,task.attempts); assertNull(task.staged); assertNull(task.checksum)
        } finally {
            repository.store.writableDatabase.delete("tasks","source=?",arrayOf(profile.id))
            access.sources().restore(before); repository.uploadsWifiOnly(old)
        }
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } } }
    @Test fun switchingAwayFromWifiStopsOnlyTheActiveTransfer()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkChanges")=="true")
        try {
            shell("svc wifi enable")
            await("Wi-Fi is active") { UploadNetworkPolicy.allows(app,true,false) }
            val started=CompletableDeferred<Unit>()
            val upload=async { UploadNetworkPolicy.run(app,true,true,false) {
                started.complete(Unit); awaitCancellation()
            } }
            withTimeout(10_000) { started.await() }
            shell("svc wifi disable")
            assertTrue("The active transfer pauses when the default connection leaves Wi-Fi",withTimeout(10_000) { upload.await() })
            assertTrue("A network pause must not cancel the caller or consume its retry budget",currentCoroutineContext().isActive)
            shell("svc wifi enable")
            await("Wi-Fi is active again") { UploadNetworkPolicy.allows(app,true,false) }
            assertFalse(UploadNetworkPolicy.run(app,true,true,false) { false })
        } finally { shell("svc wifi enable") }
    }
    @Test fun mobileWaitKeepsTheOriginalAndRetryBudgetAndWifiAutomaticallyResumesTheJob()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkChanges")=="true")
        NativeImportFixtures().use { fixture ->
            val access=EntryPointAccessors.fromApplication(app,SourceExtensionAccess::class.java)
            val previous=access.sources().snapshots.first(); val old=settings.uploadsWifiOnlyFlow().first()
            val repository=app.imports(); val uploads=AtomicInteger()
            val server=MockWebServer().apply { dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse=when(request.requestUrl!!.encodedPath) {
                    "/api/assets/bulk-upload-check" -> {
                        val id=JSONObject(request.body.readUtf8()).getJSONArray("assets").getJSONObject(0).getString("id")
                        MockResponse().setBody("""{"results":[{"id":"$id","action":"accept"}]}""")
                    }
                    "/api/assets" -> { uploads.incrementAndGet(); MockResponse().setBody("""{"id":"wifi-fixture","status":"created"}""") }
                    else -> MockResponse().setResponseCode(404)
                }
            }; start() }
            val profile=immichProfile("Wi-Fi transfer fixture",ImmichConfig(server.url("/").toString(),"synthetic-key"))
            val original=fixture.image("wifi-upload.png",Color.BLUE)
            try {
                access.sources().upsert(profile); repository.uploadsWifiOnly(true)
                shell("svc wifi disable")
                await("Wi-Fi is unavailable") { !UploadNetworkPolicy.allows(app,true,false) }
                repository.store.add(profile.id,profile.identity,"default",MediaDocuments.details(app.contentResolver,original.first),network=true)
                assertTrue("A blocked transfer reports waiting without opening media",repository.transfer(false))
                val task=repository.store.next(false)!!
                assertEquals(0,task.attempts); assertNull(task.staged); assertNull(task.checksum)
                assertEquals(0,uploads.get()); assertEquals(0,server.requestCount)
                ActivityScenario.launch<SourceSettingsActivity>(Intent(app,SourceSettingsActivity::class.java)).use {
                    assertTrue(ImportJobs.userInitiated(app))
                    ImportJobs.recovery(app)
                    await("UIDT is waiting for Wi-Fi") { app.getSystemService(JobScheduler::class.java).getPendingJob(6211)?.requiredNetwork?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
                    assertEquals(0,uploads.get())
                    shell("svc wifi enable")
                    await("Wi-Fi resumes the queued upload without another user action") { repository.store.stats(profile.id).done==1 }
                    assertEquals(1,uploads.get())
                    assertArrayEquals(original.second,app.contentResolver.openInputStream(original.first)!!.use { it.readBytes() })
                }
            } finally {
                shell("svc wifi enable")
                repository.store.writableDatabase.delete("tasks","source=?",arrayOf(profile.id))
                access.sources().restore(previous); repository.uploadsWifiOnly(old); server.shutdown()
            }
        }
    }
}
