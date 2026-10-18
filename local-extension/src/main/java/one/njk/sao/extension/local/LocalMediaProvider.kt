package one.njk.sao.extension.local

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

/** Read-only streams/thumbnails; no duplicate persistent media cache in the extension. */
class LocalMediaProvider : ContentProvider() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(3))
    override fun onCreate()=true
    override fun getType(uri: Uri)="application/octet-stream"
    override fun openFile(uri: Uri, mode: String)=openFile(uri,mode,null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val app=requireNotNull(context)
        val callers=app.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        require(mode=="r" && callers.any { it in setOf(app.packageName,"one.njk.sao","one.njk.sao.testing") && app.packageManager.checkSignatures(app.packageName,it)==PackageManager.SIGNATURE_MATCH })
        val parts=uri.pathSegments; require(parts.size==5)
        val pipe=ParcelFileDescriptor.createReliablePipe()
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val store=LocalStore.get(app); val library=store.library(parts[0])
                if(library.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
                val asset=store.asset(parts[2].toLong(),library.id)
                if(asset.signature!=parts[3]) throw ExtensionFailure("MEDIA_CHANGED")
                if(parts[4]=="preview") {
                    val bitmap=MediaBitmaps.read(app,Uri.parse(asset.uri),asset.mime,720)
                    try { if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))) throw java.io.IOException("Thumbnail failed") }
                    finally { bitmap.recycle() }
                } else {
                    require(parts[4]=="original")
                    coroutineScope {
                        val cancel=CancellationSignal()
                        val file=app.contentResolver.openFileDescriptor(Uri.parse(asset.uri),"r",cancel) ?: throw ExtensionFailure("HTTP_404")
                        val closer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { cancel.cancel(); file.close() } }
                        try { ParcelFileDescriptor.AutoCloseInputStream(file).use { input ->
                            val output=ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]); val buffer=ByteArray(32*1024)
                            while(true) { ensureActive(); val count=input.read(buffer); if(count<0) break; output.write(buffer,0,count) }
                        } } finally { closer.cancel(); file.close() }
                    }
                }
                pipe[1].close()
            } catch (error: Exception) {
                val code=when(error) { is ExtensionFailure -> error.code; is SecurityException -> "PERMISSION_REQUIRED"; is java.io.FileNotFoundException -> "HTTP_404"; else -> "MEDIA_UNAVAILABLE" }
                try { pipe[1].closeWithError(JSONObject().put("code",code).toString()) } catch (_: java.io.IOException) { }
            }
        }
        job.invokeOnCompletion { try { pipe[1].close() } catch (_: java.io.IOException) { } }
        signal?.setOnCancelListener { job.cancel() }; job.start(); return pipe[0]
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor?=null
    override fun insert(uri: Uri, values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
