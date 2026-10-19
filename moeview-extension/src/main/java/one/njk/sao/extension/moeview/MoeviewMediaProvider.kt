package one.njk.sao.extension.moeview

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import one.njk.sao.source.ExtensionFailure
import org.json.JSONObject

/** Read-only cancellable socket-to-pipe transfer. The host exclusively owns downloaded media. */
class MoeviewMediaProvider : ContentProvider() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(3))
    override fun onCreate()=true
    override fun getType(uri: Uri)="application/octet-stream"
    override fun openFile(uri: Uri,mode: String)=openFile(uri,mode,null)
    override fun openFile(uri: Uri,mode: String,signal: CancellationSignal?): ParcelFileDescriptor {
        val app=requireNotNull(context)
        check(mode=="r" && app.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().any {
            it in setOf("one.njk.sao","one.njk.sao.testing",app.packageName) && app.packageManager.checkSignatures(app.packageName,it)==PackageManager.SIGNATURE_MATCH
        })
        val parts=uri.pathSegments; require(parts.size==4)
        val pipe=ParcelFileDescriptor.createReliablePipe()
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val runtime=MoeviewRuntime.get(app); val profile=runtime.profiles.get(parts[0])
                if(profile.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
                val records=MoeviewRecords(runtime.profiles); val ref=records.reference(profile,parts[3])
                val variant=records.variant(ref,parts[2]); val post=records.post(profile,parts[3])
                runtime.use(profile) { api -> coroutineScope {
                    val media=api.openMedia(profile.config.board,post,variant)
                    val cancel=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { media.cancel() } }
                    try { media.use { response ->
                        val fullSize=variant==one.njk.sao.moeview.data.MediaKind.FULL || ref.getString("preview")==ref.getString("file")
                        val limit=if(fullSize) 64L*1024*1024 else 8L*1024*1024
                        if(response.contentLength>limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                        val output=ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]); val buffer=ByteArray(32*1024); var size=0L
                        val input=response.byteStream()
                        while(true) {
                            ensureActive(); val count=input.read(buffer); if(count<0) break
                            size+=count; if(size>limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                            output.write(buffer,0,count)
                        }
                    } } finally { cancel.cancel() }
                } }
                pipe[1].close()
            } catch(error: Exception) {
                val failure=MoeviewRuntime.failure(error)
                try { pipe[1].closeWithError(JSONObject().put("code",failure.code).put("retryAfter",failure.retryAfter).toString()) } catch(_: java.io.IOException) { }
            }
        }
        job.invokeOnCompletion { try { pipe[1].close() } catch(_: java.io.IOException) { } }
        signal?.setOnCancelListener { job.cancel() }; job.start(); return pipe[0]
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,order: String?): Cursor?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
