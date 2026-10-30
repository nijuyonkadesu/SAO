package one.njk.sao.importing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import one.njk.sao.R
import one.njk.sao.database.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Jobs exist only for queued work or user-configured folders. Nothing is scheduled by the floating service. */
internal object ImportJobs {
    private const val SCAN="media-import-scan"
    private const val PERIODIC="media-import-folder-check"
    private const val USER_JOB=6211
    internal fun constraints(network: Boolean=false,automatic: Boolean=false,wifiOnly: Boolean=false)=Constraints.Builder()
        .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).apply {
            if(network) setRequiredNetworkRequest(UploadNetworkPolicy.request(wifiOnly,automatic),
                if(automatic || wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        }.build()
    fun configure(context: Context,folders: Boolean) {
        val work=WorkManager.getInstance(context)
        if(folders) work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ImportFolderCheckWorker>(6,TimeUnit.HOURS)
                .setInitialDelay(6,TimeUnit.HOURS).setConstraints(constraints()).build())
        else work.cancelUniqueWork(PERIODIC)
    }
    fun scan(context: Context,continuation: Boolean=false) {
        WorkManager.getInstance(context).enqueueUniqueWork(SCAN,if(continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ImportScanWorker>().setConstraints(constraints()).build())
    }
    suspend fun enqueue(context: Context,automatic: Boolean,delay: Long=0,continuation: Boolean=false,network: Boolean=true) {
        val wifiOnly=network && SettingsDataStore(context).uploadsWifiOnlyFlow().first()
        WorkManager.getInstance(context).enqueueUniqueWork("media-import-transfer-$automatic-$network",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ImportWorker>().setInputData(workDataOf("automatic" to automatic,"network" to network))
                .setConstraints(constraints(network=network,automatic=automatic,wifiOnly=wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES)
                .setInitialDelay(delay,TimeUnit.MILLISECONDS).build())
    }
    suspend fun userInitiated(context: Context): Boolean {
        if(Build.VERSION.SDK_INT<34) return false
        val wifiOnly=SettingsDataStore(context).uploadsWifiOnlyFlow().first()
        return try { context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(USER_JOB,ComponentName(context,ImportTransferService::class.java))
            .setUserInitiated(true).setRequiredNetwork(UploadNetworkPolicy.request(wifiOnly,false)).build())==JobScheduler.RESULT_SUCCESS }
        catch(_: SecurityException) { false }
    }
    suspend fun recovery(context: Context) {
        val wifiOnly=SettingsDataStore(context).uploadsWifiOnlyFlow().first()
        WorkManager.getInstance(context).enqueueUniqueWork("media-import-user-recovery",ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ImportWorker>().setInputData(workDataOf("automatic" to false,"network" to true))
                .setConstraints(constraints(network=true,wifiOnly=wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).setInitialDelay(15,TimeUnit.MINUTES).build())
    }
    suspend fun updateNetworks(context: Context,store: ImportStore) {
        // Replace only upload jobs. Local copies and configured-folder discovery remain independent.
        context.getSystemService(JobScheduler::class.java).cancel(USER_JOB)
        val work=WorkManager.getInstance(context)
        for(name in listOf("media-import-transfer-true-true","media-import-transfer-false-true","media-import-user-recovery"))
            work.cancelUniqueWork(name).await()
        for(automatic in listOf(false,true)) store.nextDelay(automatic)?.let { delay -> enqueue(context,automatic,delay) }
    }
    fun recovered(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("media-import-user-recovery") }
    fun notification(context: Context): android.app.Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("media-imports",one.njk.sao.AppLanguages.context(context).getString(R.string.import_title),NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(context,6211,Intent(context,MediaImportActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context,"media-imports").setSmallIcon(R.drawable.ic_picker_copy)
            .setContentTitle(one.njk.sao.AppLanguages.context(context).getString(R.string.import_title)).setContentText(one.njk.sao.AppLanguages.context(context).getString(R.string.import_in_progress))
            .setContentIntent(intent).setProgress(0,0,true).setOngoing(true).build()
    }
}

class ImportFolderCheckWorker(app: Context,parameters: WorkerParameters): CoroutineWorker(app,parameters) {
    override suspend fun doWork(): Result { ImportJobs.scan(applicationContext); return Result.success() }
}
class ImportScanWorker(app: Context,parameters: WorkerParameters): CoroutineWorker(app,parameters) {
    override suspend fun doWork(): Result {
        if(!applicationContext.imports().scan()) ImportJobs.scan(applicationContext,continuation=true)
        return Result.success()
    }
}
class ImportWorker(app: Context,parameters: WorkerParameters): CoroutineWorker(app,parameters) {
    override suspend fun doWork(): Result {
        val repository=applicationContext.imports(); val automatic=inputData.getBoolean("automatic",false); val network=inputData.getBoolean("network",true)
        // A default-route change can briefly precede Android's constraint cancellation. Leave the
        // receipt/attempt count intact and use WorkManager backoff instead of making a hot chain.
        if(repository.transfer(automatic,network=network)) return Result.retry()
        val delay=repository.store.nextDelay(automatic,network)
        if(delay!=null) ImportJobs.enqueue(applicationContext,automatic,delay,continuation=true,network=network)
        else if(!automatic && network) ImportJobs.recovered(applicationContext)
        return Result.success()
    }
}

/** Android 14+ long, explicitly started uploads; ordinary folder ingestion remains deferrable WorkManager work. */
class ImportTransferService: JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val running=mutableMapOf<Int,Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        if(Build.VERSION.SDK_INT<34) return false
        setNotification(params,6211,ImportJobs.notification(this),JOB_END_NOTIFICATION_POLICY_REMOVE)
        running[params.jobId]=scope.launch {
            val owner=currentCoroutineContext()[Job]
            try { imports().transfer(automatic=false,unlimited=true) }
            finally {
                withContext(NonCancellable) {
                    val delay=imports().store.nextDelay(false)
                    if(delay!=null) ImportJobs.enqueue(this@ImportTransferService,false,delay)
                    else ImportJobs.recovered(this@ImportTransferService)
                    withContext(Dispatchers.Main) { running.remove(params.jobId,owner); jobFinished(params,false) }
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
