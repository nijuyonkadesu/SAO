package one.njk.sao.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** These broadcasts are platform FGS-start exemptions; ordinary app/window events are not. */
@AndroidEntryPoint
class FloatingRecoveryReceiver : BroadcastReceiver() {
    @Inject lateinit var runtime: FloatingRuntime

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        runtime.restore().invokeOnCompletion { pending.finish() }
    }
}
