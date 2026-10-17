package one.njk.sao.extension.immich

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import one.njk.sao.immichprovider.ImmichAdapter
import one.njk.sao.source.ExtensionFailure

/** Authenticated one-shot stream. No extension-owned media downloads or cache directory. */
class ImmichMediaProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri,mode,null)
    override fun openFile(uri: Uri, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
        val app = requireNotNull(context)
        val caller = app.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().firstOrNull {
            it in setOf("one.njk.sao","one.njk.sao.testing",app.packageName) &&
                app.packageManager.checkSignatures(app.packageName,it) == PackageManager.SIGNATURE_MATCH
        }
        check(caller != null && mode == "r") { "Read access denied" }
        val parts = uri.pathSegments; require(parts.size == 4)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val job = scope.launch(start=CoroutineStart.LAZY) {
            try {
                val connection = ConnectionStore(app).get(parts[0]); if (connection.revision != parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
                ImmichAdapter(connection.config,"stream").use { adapter -> coroutineScope {
                    val call = adapter.mediaCall(parts[3],parts[2],uri.getQueryParameter("edited") == "true")
                    val closer = launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                pipe[1].closeWithError(org.json.JSONObject().put("code","HTTP_${response.code}")
                                    .put("retryAfter",response.header("Retry-After")?.take(128)).toString())
                                return@coroutineScope
                            }
                            requireNotNull(response.body).byteStream().use { input ->
                                val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                                val buffer = ByteArray(32*1024)
                                while(true) {
                                    ensureActive()
                                    val count = input.read(buffer); if(count < 0) break
                                    output.write(buffer,0,count)
                                }
                            }
                        }
                    } finally { closer.cancel() }
                } }
                pipe[1].close()
            } catch (error: Exception) {
                val code = when(error) { is ExtensionFailure -> error.code; is javax.net.ssl.SSLException -> "TLS"; else -> "NETWORK" }
                try { pipe[1].closeWithError(org.json.JSONObject().put("code",code).toString()) } catch (_: java.io.IOException) { }
            }
        }
        job.invokeOnCompletion {
            try { pipe[1].close() } catch (_: java.io.IOException) { }
        }
        signal?.setOnCancelListener { job.cancel() }
        job.start()
        return pipe[0]
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
