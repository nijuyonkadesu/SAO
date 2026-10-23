package one.njk.sao.floating

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.text.format.DateFormat
import one.njk.sao.R
import java.util.Date

/** Own-process history only. Read once on settings resume, on IO; never inspect other apps. */
internal object FloatingExitInfo {
    fun read(context: Context): String? {
        val exit = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        val label = when (exit.reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> R.string.floating_exit_stopped
            ApplicationExitInfo.REASON_LOW_MEMORY -> R.string.floating_exit_memory
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> R.string.floating_exit_crash
            ApplicationExitInfo.REASON_ANR -> R.string.floating_exit_anr
            ApplicationExitInfo.REASON_SIGNALED -> R.string.floating_exit_signal
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> R.string.floating_exit_update
            else -> R.string.floating_exit_other
        }
        val time = Date(exit.timestamp)
        val formatted = "${DateFormat.getDateFormat(context).format(time)} ${DateFormat.getTimeFormat(context).format(time)}"
        return context.getString(R.string.floating_exit_detail, context.getString(label), formatted)
    }
}
