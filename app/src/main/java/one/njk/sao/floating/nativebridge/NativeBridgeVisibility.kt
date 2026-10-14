package one.njk.sao.floating.nativebridge

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** URI grants make the granting package visible to recipients on Android 11+.
 * Host manifests cannot declare SAO, so bootstrap only visibility with an empty, isolated
 * file. This grants no media, settings or API credentials; the bridge still checks UID.
 */
internal object NativeBridgeVisibility {
    private val hosts = setOf("com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.discord")
    suspend fun prepare(context: Context) = withContext(Dispatchers.IO) {
        val uri = try {
            val file = File(context.filesDir, "native_bridge/ready").apply {
                parentFile?.mkdirs()
                if (!exists()) createNewFile()
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: java.io.IOException) { return@withContext }
        catch (_: IllegalArgumentException) { return@withContext }
        for (host in hosts) try {
            context.packageManager.getApplicationInfo(host, 0)
            context.grantUriPermission(host, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
        catch (_: SecurityException) { }
        catch (_: IllegalArgumentException) { } // Package uninstalled between lookup and grant.
    }
}
