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
import java.util.concurrent.TimeUnit

/** Jobs exist only for queued work or user-configured folders. Nothing is scheduled by the floating service. */
internal object ImportJobs {
    private const val SCAN="media-import-scan"
    private const val PERIODIC="media-import-folder-check"
    private fun constraints(network: Boolean=false,automatic: Boolean=false)=Constraints.Builder()
        .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).apply {
            if(network) setRequiredNetworkType(if(automatic) NetworkType.UNMETERED else NetworkType.CONNECTED)
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
    fun enqueue(context: Context,automatic: Boolean,delay: Long=0,continuation: Boolean=false,network: Boolean=true) {
        WorkManager.getInstance(context).enqueueUniqueWork("media-import-transfer-$automatic-$network",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ImportWorker>().setInputData(workDataOf("automatic" to automatic,"network" to network))
                .setConstraints(constraints(network=network,automatic=automatic)).setInitialDelay(delay,TimeUnit.MILLISECONDS).build())
    }
    fun userInitiated(context: Context): Boolean {
        if(Build.VERSION.SDK_INT<34) return false
        return try { context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(6211,ComponentName(context,ImportTransferService::class.java))
            .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())==JobScheduler.RESULT_SUCCESS }
        catch(_: SecurityException) { false }
    }
    fun recovery(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("media-import-user-recovery",ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ImportWorker>().setInputData(workDataOf("automatic" to false,"network" to true))
                .setConstraints(constraints(network=true)).setInitialDelay(15,TimeUnit.MINUTES).build())
    }
    fun recovered(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("media-import-user-recovery") }
    fun notification(context: Context): android.app.Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("media-imports",context.getString(R.string.import_title),NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(context,6211,Intent(context,MediaImportActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context,"media-imports").setSmallIcon(R.drawable.ic_picker_copy)
            .setContentTitle(context.getString(R.string.import_title)).setContentText(context.getString(R.string.import_in_progress))
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
        repository.transfer(automatic,network=network)
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
            try { imports().transfer(automatic=false,unlimited=true) }
            finally {
                val delay=imports().store.nextDelay(false)
                if(delay!=null) ImportJobs.enqueue(this@ImportTransferService,false,delay)
                else ImportJobs.recovered(this@ImportTransferService)
                withContext(NonCancellable+Dispatchers.Main) { running.remove(params.jobId); jobFinished(params,false) }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
