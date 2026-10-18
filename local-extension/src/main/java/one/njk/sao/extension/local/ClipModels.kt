package one.njk.sao.extension.local

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Verified model catalog and explicit, resumable transfers to user-owned folders. */
object ClipModels {
    const val MODEL="ViT-B-32__openai@a857c8de2c07bbcfa6646adfcf31b798845afa1e"
    const val WORK="local-context-model"
    const val TOTAL=609449193L
    data class Part(val path: String,val size: Long,val sha256: String)
    val parts get()=ClipCatalog.immich.parts
    private val changes=MutableStateFlow(0L)
    data class Status(val ready: Boolean=false,val bytes: Long=0,val work: IndexWork=IndexWork.IDLE,val failed: Boolean=false,
        val location: String?=null,val legacy: Boolean=false)
    // Read-only migration support. New downloads always require an explicitly selected SAF folder.
    fun directory(context: Context)=File(context.noBackupFilesDir,"clip-default")
    fun file(context: Context,path: String)=File(directory(context),path)
    private fun legacyReady(context: Context,model: ClipModel)=model==ClipCatalog.immich && file(context,"ready").let { marker ->
        marker.isFile && marker.readText()==MODEL && parts.all { file(context,it.path).length()==it.size }
    }
    fun ready(context: Context,model: ClipModel=ClipCatalog.immich)=ModelStorage.get(context,model)?.ready() ?: legacyReady(context,model)
    internal fun clearLegacy(context: Context) {
        // Only app-owned artifacts, after the complete external pair has passed verification.
        (parts.map { it.path }+parts.map { it.path+".partial" }+"ready").forEach { file(context,it).delete() }
        file(context,"visual").delete(); file(context,"textual").delete(); directory(context).delete()
    }
    fun descriptor(context: Context,model: ClipModel,path: String): ParcelFileDescriptor =
        ModelStorage.get(context,model)?.descriptor(path) ?: run {
            check(legacyReady(context,model)); ParcelFileDescriptor.open(file(context,path),ParcelFileDescriptor.MODE_READ_ONLY)
        }
    fun tokenizer(context: Context,model: ClipModel): String=ModelStorage.get(context,model)?.let { storage ->
        storage.input(requireNotNull(storage.document(model.tokenizer))).bufferedReader().use { it.readText() }
    } ?: file(context,model.tokenizer).readText()
    fun work(model: ClipModel)=if(model==ClipCatalog.immich) WORK else "$WORK-8bit"
    fun observe(context: Context,model: ClipModel=ClipCatalog.immich)=combine(
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(work(model)),changes) { jobs,_ -> withContext(Dispatchers.IO) {
        val storage=ModelStorage.get(context,model)
        val bytes=try { model.parts.sumOf { part ->
            if(storage!=null) { val size=storage.length(part.path); if(size==part.size) size else storage.length(part.path+".partial").coerceAtMost(part.size) }
            else if(file(context,part.path).length()==part.size && model==ClipCatalog.immich) part.size else 0
        } } catch(_: Exception) { 0L }
        val location=storage?.tree?.let { DocumentsName.display(it) }
        Status(ready(context,model),bytes,when {
            jobs.any { it.state==WorkInfo.State.RUNNING } -> IndexWork.RUNNING
            jobs.any { it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED } -> IndexWork.WAITING
            else -> IndexWork.IDLE
        },jobs.any { it.state==WorkInfo.State.FAILED },location,storage==null && legacyReady(context,model))
    } }
    fun select(context: Context,model: ClipModel,tree: Uri) {
        cancel(context,model); ModelStorage.select(context,model,tree); changes.value++
        WorkManager.getInstance(context).enqueueUniqueWork(work(model),ExistingWorkPolicy.APPEND_OR_REPLACE,request(model,tree,false))
    }
    fun download(context: Context,model: ClipModel=ClipCatalog.immich) {
        val tree=requireNotNull(ModelStorage.tree(context,model)) { "Choose a model folder first" }
        WorkManager.getInstance(context).enqueueUniqueWork(work(model),ExistingWorkPolicy.KEEP,request(model,tree,true))
    }
    fun cancel(context: Context,model: ClipModel=ClipCatalog.immich) { WorkManager.getInstance(context).cancelUniqueWork(work(model)) }
    internal fun url(part: Part)=ClipCatalog.immich.base+part.path
    internal fun request(model: ClipModel,tree: Uri,download: Boolean)=OneTimeWorkRequestBuilder<ClipDownloadWorker>()
        .setInputData(workDataOf("model" to model.id,"tree" to tree.toString(),"download" to download))
        .setConstraints(Constraints.Builder().apply { if(download) setRequiredNetworkType(NetworkType.CONNECTED) }
            .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
    internal suspend fun verify(file: File,part: Part): Boolean = file.length()==part.size && file.inputStream().buffered().use { ModelStorage.hash(it)==part.sha256 }
}
private object DocumentsName {
    fun display(uri: Uri)=android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { uri.toString() }
}

/** No hidden downloads, internal model copies, recurring jobs or custom wake locks. */
class ClipDownloadWorker(context: Context,parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result=withContext(Dispatchers.IO) {
        val model=try { ClipCatalog.get(requireNotNull(inputData.getString("model"))) } catch(_: Exception) { return@withContext Result.failure() }
        val tree=Uri.parse(inputData.getString("tree") ?: return@withContext Result.failure())
        if(ModelStorage.tree(applicationContext,model)!=tree) return@withContext Result.success()
        val storage=ModelStorage(applicationContext,model,tree)
        val download=inputData.getBoolean("download",false)
        val deadline=SystemClock.elapsedRealtime()+120_000
        val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
        var lastProgress=0L
        fun continueLater() { WorkManager.getInstance(applicationContext).enqueueUniqueWork(ClipModels.work(model),ExistingWorkPolicy.APPEND_OR_REPLACE,ClipModels.request(model,tree,download)) }
        try {
            if(storage.ready()) { if(model==ClipCatalog.immich) ClipModels.clearLegacy(applicationContext); return@withContext Result.success() }
            for(part in model.parts) {
                ensureActive()
                if(SystemClock.elapsedRealtime()>=deadline) { continueLater(); return@withContext Result.success() }
                val target=storage.document(part.path)
                if(target!=null) { check(storage.verify(target,part)) { "Existing model file does not match the selected model" }; continue }
                val legacy=ClipModels.file(applicationContext,part.path).takeIf { model==ClipCatalog.immich && it.length()==part.size && ClipModels.verify(it,part) }
                if(!download && legacy==null) return@withContext Result.success()
                val partial=requireNotNull(storage.document(part.path+".partial",true))
                val size=storage.info(partial).size
                val offset=size.takeIf { it<=part.size } ?: 0L
                if(offset<part.size) {
                    if(legacy!=null) {
                        legacy.inputStream().use { input ->
                            input.channel.position(offset)
                            storage.output(partial,offset).buffered().use { output ->
                                val buffer=ByteArray(256*1024)
                                while(true) {
                                    ensureActive()
                                    if(SystemClock.elapsedRealtime()>=deadline) { output.flush(); continueLater(); return@withContext Result.success() }
                                    val count=input.read(buffer); if(count<0) break; output.write(buffer,0,count)
                                    val now=SystemClock.elapsedRealtime()
                                    if(now-lastProgress>1000) { output.flush(); setProgress(workDataOf("bytes" to storage.info(partial).size)); lastProgress=now }
                                }
                            }
                        }
                    } else {
                        val request=Request.Builder().url(model.base+part.path).header("Accept-Encoding","identity")
                            .apply { if(offset>0) header("Range","bytes=$offset-") }.build()
                        val call=client.newCall(request)
                        val closer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                        try { call.execute().use { response ->
                            if(response.code==429 || response.code in 500..599) throw java.io.IOException("Model host temporarily unavailable")
                            check(response.code==200 || response.code==206) { "Model download rejected" }
                            if(response.code==206) check(response.header("Content-Range")?.startsWith("bytes $offset-")==true)
                            val start=if(response.code==206) offset else 0L
                            storage.output(partial,start).buffered().use { output ->
                                val input=requireNotNull(response.body).byteStream(); val buffer=ByteArray(256*1024); var written=start
                                while(true) {
                                    ensureActive()
                                    if(SystemClock.elapsedRealtime()>=deadline) { output.flush(); continueLater(); return@withContext Result.success() }
                                    val count=input.read(buffer); if(count<0) break
                                    written+=count; check(written<=part.size); output.write(buffer,0,count)
                                    val now=SystemClock.elapsedRealtime()
                                    if(now-lastProgress>1000) { output.flush(); setProgress(workDataOf("bytes" to written)); lastProgress=now }
                                }
                            }
                        } } finally { closer.cancel() }
                    }
                }
                if(!storage.verify(partial,part)) { android.provider.DocumentsContract.deleteDocument(applicationContext.contentResolver,partial); error("Model checksum mismatch") }
                check(android.provider.DocumentsContract.renameDocument(applicationContext.contentResolver,partial,part.path.substringAfterLast('/'))!=null)
            }
            ensureActive()
            check(storage.validate())
            if(model==ClipCatalog.immich) ClipModels.clearLegacy(applicationContext)
            Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: java.io.IOException) { if(download && runAttemptCount<2) Result.retry() else Result.failure() }
        catch(_: Exception) { Result.failure() }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
