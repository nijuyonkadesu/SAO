package one.njk.sao.extensions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

internal data class InstallEvent(val session: Int,val packageName: String,val status: Int,val confirmation: Intent?)
@Singleton
class ExtensionInstallEvents @Inject constructor() {
    private val mutable=MutableStateFlow<InstallEvent?>(null)
    internal val state=mutable.asStateFlow()
    internal fun receive(event: InstallEvent) { mutable.value=event }
    internal fun consume(event: InstallEvent) { mutable.compareAndSet(event,null) }
}
@EntryPoint @InstallIn(SingletonComponent::class)
interface ExtensionInstallerAccess { fun installEvents(): ExtensionInstallEvents }

/** Explicit, non-exported PendingIntent target. Never starts an Activity in the background. */
class ExtensionInstallReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pkg=intent.getStringExtra("extension_package") ?: return
        val session=intent.getIntExtra("extension_session",-1)
        if(session<0 || !ExtensionCatalog.validPackage(pkg)) return
        val event=InstallEvent(session,pkg,intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE),
            androidx.core.content.IntentCompat.getParcelableExtra(intent,Intent.EXTRA_INTENT,Intent::class.java))
        EntryPointAccessors.fromApplication(context.applicationContext,ExtensionInstallerAccess::class.java).installEvents().receive(event)
    }
}

@Singleton
class ExtensionInstaller @Inject constructor(@ApplicationContext private val context: Context,private val repository: CatalogRepository) {
    internal suspend fun install(verified: VerifiedExtension,created: (Int)->Unit = {}) = withContext(Dispatchers.IO) {
        // Recheck after the user's settings/permission detour or another installer completing.
        repository.validate(verified.entry,verified.artifact,verified.file)
        val installer=context.packageManager.packageInstaller
        // Retire this updater's unfinished attempt for the same source, without touching other apps/installers.
        installer.mySessions.filter { it.appPackageName==verified.entry.packageName }.forEach { installer.abandonSession(it.sessionId) }
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(verified.entry.packageName); setSize(verified.file.length())
            if(Build.VERSION.SDK_INT>=31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val session=installer.createSession(params)
        created(session)
        var committed=false
        try {
            installer.openSession(session).use { handle ->
                handle.openWrite("base.apk",0,verified.file.length()).use { output ->
                    verified.file.inputStream().use { input ->
                        val buffer=ByteArray(32*1024)
                        while(true) { currentCoroutineContext().ensureActive(); val count=input.read(buffer); if(count<0) break; output.write(buffer,0,count) }
                    }
                    handle.fsync(output)
                }
                currentCoroutineContext().ensureActive()
                val callback=Intent(context,ExtensionInstallReceiver::class.java)
                    .setAction("${context.packageName}.EXTENSION_INSTALL.$session")
                    .putExtra("extension_package",verified.entry.packageName).putExtra("extension_session",session)
                val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0
                handle.commit(PendingIntent.getBroadcast(context,session,callback,flags).intentSender)
                committed=true
            }
        } finally { if(!committed) installer.abandonSession(session) }
    }
}
