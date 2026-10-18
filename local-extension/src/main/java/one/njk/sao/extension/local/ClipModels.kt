package one.njk.sao.extension.local

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Fixed, checksum-pinned Immich default. Downloads model weights only; media never leaves the device. */
object ClipModels {
    const val MODEL="ViT-B-32__openai@a857c8de2c07bbcfa6646adfcf31b798845afa1e"
    const val WORK="local-context-model"
    const val TOTAL=609449193L
    private const val BASE="https://huggingface.co/immich-app/ViT-B-32__openai/resolve/a857c8de2c07bbcfa6646adfcf31b798845afa1e/"
    data class Part(val path: String, val size: Long, val sha256: String)
    val parts=listOf(
        Part("visual/model.onnx",351613724,"33a3df41ceef21acdf371af00f6dd0456ec1f9eba24d03a7720f9c3734e40859"),
        Part("textual/model.onnx",254193396,"b80cf0af751533a6712d92247f0ddc0c95208748bc59a1a27f33e67be6864e3b"),
        Part("textual/tokenizer.json",3642073,"6d9109cc838977f3ca94a379eec36aecc7c807e1785cd729660ca2fc0171fb35"))
    data class Status(val ready: Boolean=false, val bytes: Long=0, val work: IndexWork=IndexWork.IDLE, val failed: Boolean=false)
    fun directory(context: Context)=File(context.noBackupFilesDir,"clip-default")
    fun file(context: Context, path: String)=File(directory(context),path)
    fun ready(context: Context)=file(context,"ready").let { marker ->
        marker.isFile && marker.readText()==MODEL && parts.all { file(context,it.path).length()==it.size }
    }
    fun observe(context: Context)=WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK).map { jobs ->
        withContext(Dispatchers.IO) {
            val running=jobs.firstOrNull { it.state==WorkInfo.State.RUNNING }
            Status(ready(context),parts.sumOf { part ->
                if(file(context,part.path).length()==part.size) part.size else file(context,part.path+".partial").length().coerceAtMost(part.size)
            },when {
                running!=null -> IndexWork.RUNNING
                jobs.any { it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED } -> IndexWork.WAITING
                else -> IndexWork.IDLE
            },jobs.any { it.state==WorkInfo.State.FAILED })
        }
    }
    fun download(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,request())
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    internal fun url(part: Part)=BASE+part.path
    internal fun request()=OneTimeWorkRequestBuilder<ClipDownloadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
    internal suspend fun verify(file: File, part: Part): Boolean {
        if(file.length()!=part.size) return false
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes=ByteArray(256*1024)
            while(true) { currentCoroutineContext().ensureActive(); val count=input.read(bytes); if(count<0) break; hash.update(bytes,0,count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }==part.sha256
    }
}

/** Resumable bounded transfers, with a verified atomic marker only after every artifact is complete. */
class ClipDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result=withContext(Dispatchers.IO) {
        if(ClipModels.ready(applicationContext)) return@withContext Result.success()
        val deadline=SystemClock.elapsedRealtime()+120_000
        val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
        var lastProgress=0L
        try {
            for(part in ClipModels.parts) {
                val target=ClipModels.file(applicationContext,part.path).apply { parentFile!!.mkdirs() }
                if(ClipModels.verify(target,part)) continue
                target.delete()
                val partial=File(target.path+".partial")
                if(partial.length()>part.size) partial.delete()
                if(partial.length()<part.size) {
                    val offset=partial.length()
                    val request=Request.Builder().url(ClipModels.url(part)).header("Accept-Encoding","identity")
                        .apply { if(offset>0) header("Range","bytes=$offset-") }.build()
                    val call=client.newCall(request)
                    val closer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                    try { call.execute().use { response ->
                        if(response.code==429 || response.code in 500..599) throw java.io.IOException("Model host temporarily unavailable")
                        check(response.code==200 || response.code==206) { "Model download rejected" }
                        val append=offset>0 && response.code==206
                        if(response.code==206) check(response.header("Content-Range")?.startsWith("bytes $offset-")==true)
                        val input=requireNotNull(response.body).byteStream()
                        java.io.FileOutputStream(partial,append).buffered().use { output ->
                            val buffer=ByteArray(256*1024); var written=if(append) offset else 0L
                            while(true) {
                                ensureActive()
                                if(SystemClock.elapsedRealtime()>=deadline) {
                                    output.flush(); continueLater(); return@withContext Result.success()
                                }
                                val count=input.read(buffer); if(count<0) break
                                written+=count; check(written<=part.size) { "Model exceeded declared size" }
                                output.write(buffer,0,count)
                                val now=SystemClock.elapsedRealtime()
                                if(now-lastProgress>1000) { output.flush(); setProgress(workDataOf("bytes" to written)); lastProgress=now }
                            }
                        }
                    } } finally { closer.cancel() }
                }
                if(!ClipModels.verify(partial,part)) { partial.delete(); error("Model checksum mismatch") }
                check(partial.renameTo(target)) { "Could not install model" }
            }
            ClipModels.file(applicationContext,"ready.partial").apply { writeText(ClipModels.MODEL) }
                .let { check(it.renameTo(ClipModels.file(applicationContext,"ready"))) }
            Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: java.io.IOException) { if(runAttemptCount<2) Result.retry() else Result.failure() }
        catch(_: Exception) { Result.failure() }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private fun continueLater() { WorkManager.getInstance(applicationContext).enqueueUniqueWork(ClipModels.WORK,ExistingWorkPolicy.APPEND_OR_REPLACE,ClipModels.request()) }
}
