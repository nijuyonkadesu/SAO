package one.njk.sao.floating

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import one.njk.sao.R
import one.njk.sao.database.SettingsDataStore
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Install Immich with an independent test certificate; never accesses a personal server/library. */
class ExtensionTrustDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private val extensions get()=testExtensions(app)
    private val trust get()=EntryPointAccessors.fromApplication(app,ExtensionTrustAccess::class.java).extensionTrust()
    private val packageName="one.njk.sao.extension.immich"
    private val component=ComponentName(packageName,"$packageName.ImmichExtensionService")
    private suspend fun isolated(block: suspend ()->Unit) {
        assumeFalse("Requires independently signed Immich fixture",SourceSignatures.samePublisher(app,packageName))
        val settings=SettingsDataStore(app)
        val prior=settings.extensionApprovalsFlow().first().firstOrNull { it.startsWith("$packageName|") }?.substringAfter('|')
        try { trust.revoke(packageName); block() }
        finally { settings.extensionApproval(packageName,prior) }
    }
    private suspend fun fails(block: suspend ()->Unit) {
        try { block(); fail("Unapproved extension was accepted") }
        catch(error: ExtensionFailure) { assertEquals("UNTRUSTED",error.code) }
    }
    @Test fun metadataDiscoveryDoesNotAuthorizeOrBindADifferentPublisher(): Unit=runBlocking { isolated {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.packageManager.checkPermission(SourceAccess.PERMISSION,packageName))
        assertEquals(ExtensionApproval.UNTRUSTED,extensions.installedApps().single { it.packageName==packageName }.approval)
        assertTrue(extensions.candidates().any { it.component==component })
        assertFalse(extensions.installed().any { it.component==component })
        fails { ExtensionClient(app,component).profiles() }
        assertEquals(ExtensionApproval.UNTRUSTED,trust.status(packageName))
    } }
    @Test fun approvalPinsTheCurrentCertificateAndRevocationBlocksAnAlreadyBoundService(): Unit=runBlocking { isolated {
        val signer=SourceSignatures.identity(app,packageName)
        fails { trust.approve(packageName,"0".repeat(64)) }
        trust.approve(packageName,signer)
        assertEquals(ExtensionApproval.USER,trust.status(packageName))
        assertEquals(ExtensionApproval.USER,ExtensionTrust(app,SettingsDataStore(app)).status(packageName))
        assertEquals(ExtensionApproval.UNTRUSTED,trust.status(packageName,"0".repeat(64)))
        val ready=CompletableDeferred<ISourceExtension>()
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) { ready.complete(ISourceExtension.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        assertTrue(app.bindService(Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE))
        try {
            val remote=withTimeout(5000) { ready.await() }
            assertEquals(ExtensionProtocol.VERSION,JSONObject(remote.descriptor()).getInt("api"))
            trust.revoke(packageName)
            var denied=false
            try { remote.descriptor() } catch(_: RuntimeException) { denied=true }
            assertTrue("Revoked caller retained access on existing Binder",denied)
            fails { ExtensionClient(app,component).profiles() }
        } finally { app.unbindService(connection) }
    } }
    @Test fun openingAnUnapprovedSourceRequiresTheSameWarningAndCancelKeepsItBlocked(): Unit=runBlocking { isolated {
        val candidate=extensions.candidates().single { it.component==component }
        ActivityScenario.launch<SourceSetupActivity>(SourceSetupActivity.intent(app,candidate)).use { scenario ->
            waitFor("Setup warning") {
                var shown=false
                scenario.onActivity { shown=(it.supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT) as? ExtensionTrustDialog)?.dialog?.isShowing==true }
                shown
            }
            scenario.onActivity { activity ->
                val dialog=(activity.supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT) as ExtensionTrustDialog).dialog as AlertDialog
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            waitFor("Cancelled source setup finishes") { scenario.state==androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        assertEquals(ExtensionApproval.UNTRUSTED,trust.status(packageName))
    } }
    @Test fun allowingAThirdPartyNeverMigratesLegacyCredentialsIntoIt(): Unit=runBlocking { isolated {
        trust.approve(packageName,SourceSignatures.identity(app,packageName))
        val directory=java.io.File(app.cacheDir,"trust-migration-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context=object: android.content.ContextWrapper(app) { override fun getNoBackupFilesDir()=directory }
        try {
            val profiles=SourceProfiles(context,FloatingDispatchers(),mapOf("extension" to ExtensionConfigurationCodec()))
            val legacy=SourceProfile(java.util.UUID.randomUUID().toString(),"Legacy",UnknownSourceConfiguration("immich","{\"key\":\"never-forward-this\"}"))
            profiles.upsert(legacy)
            val localExtensions=SourceExtensions(context,profiles,trust)
            localExtensions.migrate(localExtensions.installed().single { it.component==component })
            assertEquals(listOf(legacy),profiles.current().profiles)
            assertFalse(ExtensionClient(app,component).profiles().any { it.id==legacy.id })
        } finally { directory.deleteRecursively() }
    } }
    @Test fun identicalProfileIdsUnderDifferentSignersHaveSeparateMediaCacheIdentities(): Unit=runBlocking { isolated {
        trust.approve(packageName,SourceSignatures.identity(app,packageName))
        val extension=extensions.installed().single { it.component==component }
        val record=ExtensionProfile("same-profile","Source","same-revision")
        val first=extension.profile(record)
        val other=extension.copy(signer="f".repeat(64)).profile(record)
        assertEquals(first.id,other.id); assertNotEquals(first.identity,other.identity)
        val codec=ExtensionConfigurationCodec()
        assertEquals(first.configuration,codec.decode(codec.encode(first.configuration)))
        try { extensions.requireInstalled((first.configuration as ExtensionConfig).copy(signer="f".repeat(64))); fail("Stale signer accepted") }
        catch(error: ExtensionFailure) { assertEquals("PROFILE_CHANGED",error.code) }
    } }
    private fun waitFor(label: String,block: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<until) { if(block()) return; SystemClock.sleep(40) }
        fail(label)
    }
    @Test fun warningSurvivesRotationCancelDoesNotGrantAndTheUserCanAllowThenRevoke(): Unit=runBlocking { isolated {
        ActivityScenario.launch<SourceSettingsActivity>(Intent(app,SourceSettingsActivity::class.java)).use { scenario ->
            fun current()=runBlocking { trust.status(packageName) }
            fun control(activity: SourceSettingsActivity): View? {
                val list=activity.findViewById<RecyclerView>(R.id.source_list)
                for(index in 0 until list.childCount) {
                    val row=list.getChildAt(index)
                    if(row.findViewById<TextView>(R.id.details)?.text?.contains(packageName)==true) return row.findViewById(R.id.trust)
                }
                val state=ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value
                val index=state.installedExtensions.indexOfFirst { it.packageName==packageName }
                if(index>=0) list.scrollToPosition(1+state.catalog.profiles.size+1+index)
                return null
            }
            fun clickReview() { waitFor("Installed extension action") {
                var clicked=false
                scenario.onActivity { activity -> control(activity)?.takeIf { it.isEnabled }?.let { clicked=it.performClick() } }
                clicked
            } }
            fun dialog(): AlertDialog? {
                var found: AlertDialog?=null
                scenario.onActivity { found=(it.supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT) as? ExtensionTrustDialog)?.dialog as? AlertDialog }
                return found
            }
            clickReview(); waitFor("Warning shown") { dialog()?.isShowing==true }
            scenario.recreate(); waitFor("Warning restored") { dialog()?.isShowing==true }
            assertTrue(dialog()!!.findViewById<TextView>(android.R.id.message)!!.text.contains("SHA-256"))
            pauseSourceVisual("extension-trust-warning")
            scenario.onActivity { activity -> ((activity.supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT) as ExtensionTrustDialog).dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            assertEquals(ExtensionApproval.UNTRUSTED,current())
            clickReview(); waitFor("Warning shown again") { dialog()?.isShowing==true }
            scenario.onActivity { activity -> ((activity.supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT) as ExtensionTrustDialog).dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            waitFor("Pinned approval persisted") { current()==ExtensionApproval.USER }
            waitFor("Allowed status visible") {
                var ready=false
                scenario.onActivity { activity -> ready=ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value.installedExtensions.any { it.packageName==packageName && it.approval==ExtensionApproval.USER } }
                ready
            }
            pauseSourceVisual("extension-trust-allowed")
            clickReview()
            waitFor("Revocation persisted") { current()==ExtensionApproval.UNTRUSTED }
            fails { ExtensionClient(app,component).profiles() }
        }
    } }
    @Test fun foreignSettingsReverseUploadControlsAndMediaStreamsWorkOnlyAfterApproval(): Unit=runBlocking { isolated {
        val png=ByteArrayOutputStream().also { output -> Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN); compress(Bitmap.CompressFormat.PNG,100,output); recycle() } }.toByteArray()
        val server=MockWebServer().apply { dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest)=when {
                request.path=="/api/server/features" -> MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                request.path=="/api/api-keys/me" -> MockResponse().setBody("""{"id":"fixture","name":"Fixture","permissions":["all"]}""")
                request.path=="/api/server/version" -> MockResponse().setBody("""{"major":3,"minor":3,"patch":1}""")
                request.path=="/api/tags" -> MockResponse().setBody("[]")
                request.path?.contains("original")==true -> MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(png))
                request.path?.startsWith("/api/search/")==true -> MockResponse().setBody("""{"assets":{"items":[{"id":"fixture","type":"IMAGE","originalFileName":"fixture.png","originalMimeType":"image/png","updatedAt":"v1","fileCreatedAt":"2025-01-01T00:00:00Z","localDateTime":"2025-01-01T00:00:00Z","isAnimated":false}]}}""")
                else -> MockResponse().setBody("[]")
            }
        }; start() }
        val access=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)
        val profiles=access.sources(); val previous=profiles.snapshots.first()
        var row: SourceProfile?=null
        try {
            trust.approve(packageName,SourceSignatures.identity(app,packageName))
            val profile=immichProfile("Independent publisher",ImmichConfig(server.url("/").toString(),"synthetic-key")); row=profile
            profiles.upsert(profile,true)
            val config=profile.configuration as ExtensionConfig
            ExtensionForm(profile).use { form ->
                assertTrue(form.text("server_url").contains(server.hostName))
                try { form.await("intake_add_files") { it.isEnabled } }
                catch(error: AssertionError) {
                    val reason=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId("$packageName:id/intake_error")?.firstOrNull()?.text
                    throw AssertionError("Cross-signer intake failed: $reason",error)
                }
                form.click("intake_add_files")
                waitFor("Host callback opens the real system Files picker") {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.google.android.documentsui"
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                form.await("source_name")
            }
            val client=ExtensionClient(app,component)
            val payload=JSONObject().put("revision",config.revision).put("source",profile.identity)
            val page=SourceWire.page(client.request(config.profile,ExtensionProtocol.SEARCH,JSONObject(payload.toString()).put("query",SourceWire.query(MediaQuery("fixture")))))
            val item=page.items.single()
            val uri=android.net.Uri.parse(client.request(config.profile,ExtensionProtocol.MEDIA,JSONObject(payload.toString()).put("item",SourceWire.item(item)).put("kind","original")).getString("uri"))
            assertArrayEquals(png,app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            trust.revoke(packageName)
            var denied=false
            try { app.contentResolver.openFileDescriptor(uri,"r")?.close() } catch(_: Exception) { denied=true }
            assertTrue("Previously granted URI remained readable after revoke",denied)
            fails { client.profiles() }
        } finally {
            // Delete only synthetic data while its signer is temporarily approved for teardown.
            trust.approve(packageName,SourceSignatures.identity(app,packageName))
            row?.let { val config=it.configuration as ExtensionConfig
                ExtensionClient(app,component).request(config.profile,ExtensionProtocol.DELETE_PROFILE,JSONObject().put("revision",config.revision)) }
            profiles.restore(previous); server.shutdown()
        }
    } }
}
