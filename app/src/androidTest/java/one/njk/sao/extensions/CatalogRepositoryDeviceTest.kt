package one.njk.sao.extensions

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import one.njk.sao.source.SourceSignatures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Synthetic metadata and a supplied development APK; no private servers or media. */
class CatalogRepositoryDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private val signer get()=SourceSignatures.identity(app,app.packageName)
    private val file get()=File(app.cacheDir,"catalog_fixture.apk").also { org.junit.Assume.assumeTrue("Stage a development FindThatMeme APK in cache/catalog_fixture.apk",it.isFile) }
    private fun artifact(bytes: ByteArray)=ExtensionArtifact("universal","https://github.com/nijuyonkadesu/SAO-extensions/releases/download/test/findthatmeme.apk",
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },bytes.size.toLong())
    private fun entry(bytes: ByteArray): CatalogExtension {
        val info=requireNotNull(app.packageManager.getPackageArchiveInfo(file.path,0))
        return CatalogExtension("findthatmeme",info.packageName,"Meme fixture","Synthetic catalog",info.longVersionCode,info.versionName!!,1,30,signer,"development",listOf(artifact(bytes)))
    }
    private fun requireUpgradableFixture() {
        val target=entry(file.readBytes())
        val installed=try { app.packageManager.getPackageInfo(target.packageName,0).longVersionCode } catch(_: android.content.pm.PackageManager.NameNotFoundException) { -1L }
        org.junit.Assume.assumeTrue("The fixture must be newer than the installed APK (or absent on a disposable test device)",installed<target.versionCode)
    }
    private fun json(entry: CatalogExtension): JSONObject=JSONObject().put("schemaVersion",1).put("extensions",JSONArray().put(JSONObject()
        .put("id",entry.id).put("packageName",entry.packageName).put("name",entry.name).put("description",entry.description)
        .put("versionCode",entry.versionCode).put("versionName",entry.versionName).put("apiVersion",1).put("minSdk",30)
        .put("signerSha256",signer).put("artifacts",JSONArray().put(JSONObject().put("abi","universal").put("url",entry.artifacts.single().url)
            .put("sha256",entry.artifacts.single().sha256).put("size",entry.artifacts.single().size)))))
    private fun http(server: MockWebServer)=CatalogHttp(OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build())
    }.build())
    private suspend fun failure(issue: CatalogIssue,block: suspend ()->Unit) {
        try { block(); fail("Invalid update was accepted") } catch(error: CatalogFailure) { assertEquals(issue,error.issue) }
    }
    @Test fun catalogRejectsUnapprovedPublishersWrongPackagesDuplicateEntriesAndUnsafeUrls() {
        val row=entry(file.readBytes()); val valid=json(row)
        assertEquals(row.packageName,ExtensionCatalog.decode(valid.toString(),signer).single().packageName)
        val icon="https://raw.githubusercontent.com/nijuyonkadesu/SAO-extensions/repo/icons/findthatmeme.svg"
        val decorated=JSONObject(valid.toString()).apply { getJSONArray("extensions").getJSONObject(0).put("iconUrl",icon) }
        assertEquals(icon,ExtensionCatalog.decode(decorated.toString(),signer).single().iconUrl)
        decorated.getJSONArray("extensions").getJSONObject(0).put("iconUrl","https://unrelated.example/icon.svg")
        assertNull(ExtensionCatalog.decode(decorated.toString(),signer).single().iconUrl)

        for(change in listOf<(JSONObject)->Unit>(
            { it.put("schemaVersion",2) },
            { it.getJSONArray("extensions").getJSONObject(0).put("signerSha256","f".repeat(64)) },
            { it.getJSONArray("extensions").getJSONObject(0).put("packageName","com.example.unrelated") },
            { it.getJSONArray("extensions").put(it.getJSONArray("extensions").getJSONObject(0)) },
            { it.getJSONArray("extensions").getJSONObject(0).getJSONArray("artifacts").getJSONObject(0).put("url","http://github.com/anything.apk") },
            { it.getJSONArray("extensions").getJSONObject(0).getJSONArray("artifacts").getJSONObject(0).put("size",ExtensionCatalog.MAX_APK_BYTES+1) }
        )) {
            val bad=JSONObject(valid.toString()).also(change)
            try { ExtensionCatalog.decode(bad.toString(),signer); fail("Invalid catalog accepted") } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun downloadedApkMustMatchHashPackageVersionSigningCertificateAndProtocol(): Unit=runBlocking {
        requireUpgradableFixture()
        val bytes=file.readBytes(); val row=entry(bytes); val repository=CatalogRepository(app,http(MockWebServer()))
        repository.validate(row,row.artifacts.single(),file)
        failure(CatalogIssue.DOWNLOAD) { repository.validate(row,row.artifacts.single().copy(sha256="0".repeat(64)),file) }
        failure(CatalogIssue.INVALID) { repository.validate(row.copy(packageName="one.njk.sao.extension.moeview"),row.artifacts.single(),file) }
        failure(CatalogIssue.INVALID) { repository.validate(row.copy(versionCode=row.versionCode+1),row.artifacts.single(),file) }
        failure(CatalogIssue.SIGNATURE) { repository.validate(row.copy(signerSha256="f".repeat(64)),row.artifacts.single(),file) }
        failure(CatalogIssue.INCOMPATIBLE) { repository.validate(row.copy(apiVersion=2),row.artifacts.single(),file) }
    }
    @Test fun failedDownloadsDoNotPoisonRetryAndNeverRetryThemselves(): Unit=runBlocking {
        requireUpgradableFixture()
        val bytes=file.readBytes(); val row=entry(bytes); val server=MockWebServer().apply { start() }
        val repository=CatalogRepository(app,http(server))
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            failure(CatalogIssue.NETWORK) { repository.download(row) {} }
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val verified=repository.download(row) {}
            try { assertArrayEquals(bytes,verified.file.readBytes()); assertEquals(2,server.requestCount) }
            finally { verified.file.delete() }
        } finally { server.shutdown() }
    }
    @Test fun cancelledBodyTransferClosesTheCallAndRemovesIncompleteFiles(): Unit=runBlocking {
        requireUpgradableFixture()
        val bytes=file.readBytes(); val row=entry(bytes); val server=MockWebServer().apply { start() }
        val folder=File(app.cacheDir,"extension-install")
        val before=folder.listFiles().orEmpty().map { it.name }.toSet()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task=launch(Dispatchers.IO) { CatalogRepository(app,http(server)).download(row) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(3000) { task.cancelAndJoin() }
            assertEquals(before,folder.listFiles().orEmpty().map { it.name }.toSet())
            assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }
}
