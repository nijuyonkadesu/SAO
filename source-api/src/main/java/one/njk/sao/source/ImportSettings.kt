package one.njk.sao.source

import android.content.ComponentName
import android.net.Uri

/** Reverse, signed IPC for an extension's own import queue. No credentials or media cross this API. */
object ImportSettings {
    const val STATE="intakeState"
    const val PAUSE="intakePause"
    const val RETRY="intakeRetry"
    const val CLEAR="intakeClear"
    const val CHECK="intakeCheck"
    const val DISCONNECT="intakeDisconnect"
    const val ACTION="intakeAction"
    const val TARGET="intakeTarget"
    const val REVISION="intakeRevision"
    fun service(host: String)=ComponentName(host,"one.njk.sao.importing.ImportSettingsService")
    fun activity(host: String)=ComponentName(host,"one.njk.sao.importing.ImportActionActivity")
    fun changes(host: String)=Uri.parse("content://$host.imports/changes")
}
