package one.njk.sao.extension.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** Upgrade existing configured libraries once; WorkManager owns subsequent boot recovery. */
class LocalUpdatedReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result=goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { FolderIndexer.configureChecks(context.applicationContext) } finally { result.finish() } }
    }
}
