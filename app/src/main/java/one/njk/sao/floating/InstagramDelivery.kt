package one.njk.sao.floating

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/** Instagram's exported DM receiver; never falls through to Feed or Stories. */
object InstagramDelivery {
    const val PACKAGE = "com.instagram.android"
    const val ACTION = "com.instagram.share.ADD_TO_DIRECT_MESSAGING"

    // Instagram enables its share aliases after initialization. Resolve enabled receivers
    // on the installed version rather than bypassing that state or pinning an activity name.
    fun receiver(packages: PackageManager, mimeType: String): ComponentName? {
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) return null
        val info = packages.resolveActivity(Intent(ACTION).setPackage(PACKAGE).setType(mimeType),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        // Resolution already applies the user's effective enabled state. ActivityInfo.enabled
        // retains the manifest default even after Instagram enables an alias at runtime.
        if (info.packageName != PACKAGE || !info.exported || info.permission != null) return null
        return ComponentName(info.packageName, info.name)
    }

    fun intent(media: ExportedMedia, receiver: ComponentName, title: String): Intent {
        require(receiver.packageName == PACKAGE)
        return MediaDelivery.intent(media, PACKAGE, title).setAction(ACTION).setComponent(receiver)
    }
}
