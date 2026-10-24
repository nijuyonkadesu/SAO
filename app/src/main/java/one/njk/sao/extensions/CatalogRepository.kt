package one.njk.sao.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.source.SourceAccess
import one.njk.sao.source.SourceSignatures
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** No HTTP cache, automatic retries, observers or idle work. Called only by source settings. */
class CatalogHttp internal constructor(private val client: OkHttpClient) {
    @Inject constructor(): this(OkHttpClient.Builder().retryOnConnectionFailure(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build())
    internal suspend fun <T> receive(url: String, consume: suspend (InputStream,Long)->T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val call=client.newCall(Request.Builder().url(url).header("Cache-Control","no-cache").build())
            // Keep cancellation attached while reading the body, not only until headers arrive.
            val closer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { call.execute().use { response ->
                if(!response.isSuccessful) throw CatalogFailure(CatalogIssue.NETWORK)
                val body=requireNotNull(response.body)
                body.byteStream().use { consume(it,body.contentLength()) }
            } } finally { closer.cancel() }
        }
    }
}

internal data class VerifiedExtension(val entry: CatalogExtension,val artifact: ExtensionArtifact,val file: File)

@Singleton
class CatalogRepository @Inject constructor(@ApplicationContext private val context: Context,private val http: CatalogHttp,
    private val repositories: ExtensionRepositories) {
    private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    internal fun discard(file: File) { cleanup.launch { file.delete() } }
    internal suspend fun catalog(url: String): List<CatalogExtension> = withContext(Dispatchers.IO) {
        try {
            http.receive(url) { input,length ->
                require(length<=512*1024)
                val output=java.io.ByteArrayOutputStream()
                copy(input,output,512*1024L) {}
                ExtensionCatalog.decode(String(output.toByteArray(),Charsets.UTF_8),SourceSignatures.identity(context,context.packageName),url)
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: CatalogFailure) { throw error }
        catch(error: java.io.IOException) { currentCoroutineContext().ensureActive(); throw CatalogFailure(CatalogIssue.NETWORK,error) }
        catch(error: Exception) { currentCoroutineContext().ensureActive(); throw CatalogFailure(CatalogIssue.INVALID,error) }
    }
    internal suspend fun download(entry: CatalogExtension,progress: (Int)->Unit): VerifiedExtension {
        var created: File?=null
        try { return withContext(Dispatchers.IO) {
            requireConfigured(entry)
            val artifact=entry.artifact(Build.SUPPORTED_ABIS.toList()) ?: throw CatalogFailure(CatalogIssue.INCOMPATIBLE)
            val directory=File(context.cacheDir,"extension-install").apply { mkdirs() }
            directory.listFiles().orEmpty().filter { System.currentTimeMillis()-it.lastModified()>24L*60*60*1000 }.forEach(File::delete)
            val file=File.createTempFile("extension-", ".apk",directory).also { created=it }
            try {
                http.receive(artifact.url) { input,length ->
                    require(length<0 || length==artifact.size)
                    var last=0L
                    file.outputStream().use { output -> copy(input,output,artifact.size) { bytes ->
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(now-last>=100 || bytes==artifact.size) { progress((bytes*100/artifact.size).toInt()); last=now }
                    } }
                }
                validate(entry,artifact,file)
                VerifiedExtension(entry,artifact,file)
            } catch(cancelled: CancellationException) { file.delete(); throw cancelled }
            catch(error: CatalogFailure) { file.delete(); throw error }
            catch(error: Exception) { file.delete(); currentCoroutineContext().ensureActive(); throw CatalogFailure(CatalogIssue.DOWNLOAD,error) }
        }
        } catch(error: Exception) { withContext(NonCancellable+Dispatchers.IO) { created?.delete() }; throw error }
    }
    internal suspend fun validate(entry: CatalogExtension,artifact: ExtensionArtifact,file: File) = withContext(Dispatchers.IO) {
        requireConfigured(entry)
        if(file.length()!=artifact.size) throw CatalogFailure(CatalogIssue.DOWNLOAD)
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(32*1024)
            while(true) { currentCoroutineContext().ensureActive(); val count=input.read(buffer); if(count<0) break; hash.update(buffer,0,count) }
        }
        if(hash.digest().hex()!=artifact.sha256) throw CatalogFailure(CatalogIssue.DOWNLOAD)
        val manager=context.packageManager
        val archive=manager.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES or PackageManager.GET_META_DATA)
            ?: throw CatalogFailure(CatalogIssue.INVALID)
        val signer=archive.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.sorted()?.joinToString(",")
        if(signer!=entry.signerSha256 || entry.repositoryUrl==ExtensionCatalog.URL && signer!=SourceSignatures.identity(context,context.packageName)) throw CatalogFailure(CatalogIssue.SIGNATURE)
        if(archive.packageName!=entry.packageName || archive.longVersionCode!=entry.versionCode || archive.versionName!=entry.versionName)
            throw CatalogFailure(CatalogIssue.INVALID)
        val services=archive.services.orEmpty()
        if(entry.apiVersion!=ExtensionProtocol.VERSION || archive.applicationInfo?.minSdkVersion?.let { it>Build.VERSION.SDK_INT }!=false ||
            services.none { it.exported && it.permission==SourceAccess.PERMISSION && it.metaData?.getInt(ExtensionProtocol.API_VERSION)==ExtensionProtocol.VERSION &&
                !it.metaData?.getString(ExtensionProtocol.SETTINGS).isNullOrBlank() }) throw CatalogFailure(CatalogIssue.INCOMPATIBLE)
        val installed=try { manager.getPackageInfo(entry.packageName,0) } catch(_: PackageManager.NameNotFoundException) { null }
        if(installed!=null && SourceSignatures.identity(context,entry.packageName)!=signer) throw CatalogFailure(CatalogIssue.SIGNATURE)
        if(installed!=null && installed.longVersionCode>=entry.versionCode) throw CatalogFailure(CatalogIssue.INCOMPATIBLE)
    }
    private suspend fun requireConfigured(entry: CatalogExtension) {
        if(entry.repositoryUrl !in repositories.urls.first()) throw CatalogFailure(CatalogIssue.REMOVED)
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun copy(input: InputStream,output: java.io.OutputStream,maximum: Long,progress: (Long)->Unit) {
        val buffer=ByteArray(32*1024); var bytes=0L
        while(true) {
            currentCoroutineContext().ensureActive()
            val count=input.read(buffer); if(count<0) break
            bytes+=count; require(bytes<=maximum)
            output.write(buffer,0,count); progress(bytes)
        }
    }
}
