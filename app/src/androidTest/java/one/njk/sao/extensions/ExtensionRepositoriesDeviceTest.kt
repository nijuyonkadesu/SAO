package one.njk.sao.extensions

import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import one.njk.sao.database.SettingsDataStore
import one.njk.sao.source.SourceSignatures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic catalogs, localhost responses and a separately signed test APK; no private accounts. */
class ExtensionRepositoriesDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val settings=SettingsDataStore(app)
    private val repositories=ExtensionRepositories(settings)
    private suspend fun configure(urls: List<String>) {
        repositories.urls.first().filter { it !in urls }.forEach { repositories.remove(it) }
        urls.forEach { repositories.add(it) }
    }
    private fun json(pkg: String,name: String,signer: String,version: Long=1)=JSONObject().put("schemaVersion",1)
        .put("extensions",JSONArray().put(JSONObject().put("id","example").put("packageName",pkg).put("name",name)
            .put("description","Synthetic catalog").put("versionCode",version).put("versionName",version.toString())
            .put("apiVersion",1).put("minSdk",30).put("signerSha256",signer)
            .put("artifacts",JSONArray().put(JSONObject().put("abi","universal").put("url","https://cdn.example.invalid/source.apk")
                .put("sha256","0".repeat(64)).put("size",1)))))
    @Test fun repositoriesPersistAndAnEmptySelectionDoesNotRestoreDefaults(): Unit=runBlocking {
        val previous=repositories.urls.first()
        try {
            configure(listOf(ExtensionCatalog.URL,"https://other.example.invalid/index.json"))
            assertEquals(2,ExtensionRepositories(settings).urls.first().size)
            configure(emptyList())
            assertTrue(ExtensionRepositories(settings).urls.first().isEmpty())
            repositories.add("https://github.com/nijuyonkadesu/SAO-extensions")
            assertEquals(listOf(ExtensionCatalog.URL),repositories.urls.first())
        } finally { configure(previous) }
    }
    @Test fun nativeRepositoryDialogPreservesItsDraftAndRemovingLastRepositoryStaysEmpty(): Unit=runBlocking {
        val previous=repositories.urls.first(); val url="https://repository.example.invalid/index.json"
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        one.njk.sao.importing.NativeImportFixtures().use { controls ->
            suspend fun await(label: String,predicate: ()->Boolean) {
                try { withTimeout(10_000) { while(!predicate()) delay(30) } }
                catch(error: TimeoutCancellationException) { throw AssertionError(label,error) }
            }
            fun editor(): android.view.accessibility.AccessibilityNodeInfo? {
                fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    if(node==null) return null
                    if(node.isEditable) return node
                    repeat(node.childCount) { find(node.getChild(it))?.let { found -> return found } }
                    return null
                }
                return find(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun text(value: String) { assertTrue(editor()!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
            })) }
            fun confirm() {
                val button=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
                assertNotNull("Repository dialog confirmation missing",button)
                assertTrue(button!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            }
            try {
                configure(emptyList())
                ActivityScenario.launch<one.njk.sao.floating.SourceSettingsActivity>(android.content.Intent(app,one.njk.sao.floating.SourceSettingsActivity::class.java)).use { scenario ->
                    await("Repository actions did not render") {
                        var ready=false
                        scenario.onActivity { activity ->
                            val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.source_list)
                            list.scrollToPosition((list.adapter!!.itemCount-1).coerceAtLeast(0))
                            ready=activity.findViewById<android.view.View>(one.njk.sao.R.id.add_repository)?.isShown==true
                        }; ready
                    }
                    controls.click("Add repository")
                    await("Repository input missing") { editor()!=null }
                    text("http://example.invalid/index.json"); confirm()
                    await("Invalid URL was accepted") { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(app.getString(one.njk.sao.R.string.extension_repository_invalid))?.isNotEmpty()==true }
                    text(url); scenario.recreate()
                    await("Repository draft lost on recreation") { editor()?.text?.toString()==url }
                    confirm()
                    await("Repository URL was not saved") { runBlocking { repositories.urls.first()==listOf(url) } }
                    await("Repository row did not render") {
                        scenario.onActivity { activity ->
                            val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(one.njk.sao.R.id.source_list)
                            list.scrollToPosition(list.adapter!!.itemCount-1)
                        }
                        instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(url)?.any { it.isVisibleToUser }==true
                    }
                    one.njk.sao.floating.pauseSourceVisual("repository-settings")
                    controls.click("Remove repository $url")
                    await("Repository URL was not removed") { runBlocking { repositories.urls.first().isEmpty() } }
                    scenario.recreate()
                    assertTrue(repositories.urls.first().isEmpty())
                }
            } finally { configure(previous) }
        }
    }
    @Test fun catalogsFailIndependentlyAndConfigurationEditsOnlyFetchNewRepositories(): Unit=runBlocking {
        val previous=repositories.urls.first()
        val urls=listOf("https://a.example.invalid/index.json","https://b.example.invalid/index.json","https://c.example.invalid/index.json")
        val signer=SourceSignatures.identity(app,app.packageName)
        val concurrent=AtomicInteger(); val maximum=AtomicInteger()
        val server=MockWebServer().apply { dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val active=concurrent.incrementAndGet(); maximum.updateAndGet { maxOf(it,active) }
                try {
                    Thread.sleep(60)
                    return when(request.path) {
                        "/cdn.example.invalid" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        "/b.example.invalid" -> MockResponse().setResponseCode(503)
                        "/a.example.invalid" -> MockResponse().setBody(json("org.example.reactions","First repository",signer).toString())
                        else -> MockResponse().setBody(json("org.example.reactions","Duplicate package",signer,99).toString())
                    }
                } finally { concurrent.decrementAndGet() }
            }
        }; start() }
        val http=CatalogHttp(OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/"+chain.request().url.host)).build())
        }.build())
        val store=ViewModelStore()
        lateinit var model: ExtensionCatalogViewModel
        try {
            configure(urls)
            val repository=CatalogRepository(app,http,repositories)
            instrumentation.runOnMainSync {
                model=ExtensionCatalogViewModel(repository,ExtensionInstaller(app,repository),ExtensionInstallEvents(),SavedStateHandle(),repositories)
                store.put("catalog",model)
            }
            suspend fun loaded(count: Int)=withTimeout(12_000) { model.state.first { it.repositoriesLoaded && !it.loading && it.repositories.size==count } }
            val first=loaded(3)
            assertEquals("First repository",first.entries.single().name)
            assertEquals(setOf(urls[1]),first.repositoryErrors.keys)
            assertEquals(3,server.requestCount); assertTrue(maximum.get()<=2)
            model.removeRepository(urls[2]); loaded(2)
            assertEquals("Removing one repository must not refresh the others",3,server.requestCount)
            model.addRepository(urls[2]); loaded(3)
            withTimeout(12_000) { while(server.requestCount<4) delay(20) }
            assertEquals("Adding one repository must not retry the failed repository",4,server.requestCount)
            instrumentation.runOnMainSync { model.refresh() }
            withTimeout(12_000) { model.state.first { !it.loading && server.requestCount>=7 } }
            assertEquals(7,server.requestCount)
            // A newly added repository can take display priority for the same package.
            // Removing the actual transfer's repository must still cancel its stalled HTTP body.
            instrumentation.runOnMainSync { model.start(model.state.value.entries.single()) }
            withTimeout(12_000) { while(server.requestCount<8) delay(20) }
            val earlier="https://0.example.invalid/index.json"
            model.addRepository(earlier)
            withTimeout(12_000) { model.state.first { !it.loading && it.entries.singleOrNull()?.repositoryUrl==earlier } }
            assertEquals(9,server.requestCount)
            model.removeRepository(urls[0])
            try { withTimeout(5_000) { model.state.first { urls[0] !in it.repositories && it.activePackage==null } } }
            catch(error: TimeoutCancellationException) { throw AssertionError("Removed transfer remained active: ${model.state.value}",error) }
            assertEquals(9,server.requestCount)
        } finally { instrumentation.runOnMainSync { store.clear() }; configure(previous); server.shutdown() }
    }
    @Test fun independentCatalogPublisherMustMatchTheActualApkAndRemovedRepositoriesCannotInstall(): Unit=runBlocking {
        val file=File(app.cacheDir,"catalog_untrusted_fixture.apk")
        assertTrue("Stage the independently signed test fixture",file.isFile)
        val previous=repositories.urls.first(); val url="https://independent.example.invalid/index.json"
        val archive=requireNotNull(app.packageManager.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES))
        fun ByteArray.hash()=MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 255) }
        val signer=archive.signingInfo!!.apkContentsSigners.single().toByteArray().hash()
        val hostSigner=SourceSignatures.identity(app,app.packageName)
        assertNotEquals(hostSigner,signer)
        val metadata=json(archive.packageName,"Independent publisher",signer,archive.longVersionCode).apply {
            getJSONArray("extensions").getJSONObject(0).apply {
                put("versionName",archive.versionName)
                getJSONArray("artifacts").getJSONObject(0).put("size",file.length()).put("sha256",file.readBytes().hash())
            }
        }
        val entry=ExtensionCatalog.decode(metadata.toString(),hostSigner,url).single()
        assertFalse(entry.publisherVerified)
        try {
            configure(listOf(ExtensionCatalog.URL,url))
            val repository=CatalogRepository(app,CatalogHttp(),repositories)
            repository.validate(entry,entry.artifacts.single(),file)
            try { repository.validate(entry.copy(signerSha256=hostSigner),entry.artifacts.single(),file); fail("Wrong certificate accepted") }
            catch(error: CatalogFailure) { assertEquals(CatalogIssue.SIGNATURE,error.issue) }
            repositories.remove(url)
            try { repository.validate(entry,entry.artifacts.single(),file); fail("Removed repository accepted") }
            catch(error: CatalogFailure) { assertEquals(CatalogIssue.REMOVED,error.issue) }
        } finally { configure(previous) }
    }
}
