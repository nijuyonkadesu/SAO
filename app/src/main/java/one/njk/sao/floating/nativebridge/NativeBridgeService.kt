package one.njk.sao.floating.nativebridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Only the scoped host UID can register a client; there is no externally callable send API. */
class NativeBridgeService : Service() {
    private val bridge = object : INativeBridge.Stub() {
        override fun registerClient(packageName: String, client: INativeStickerClient) {
            if (packageName !in setOf("com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.discord"))
                throw SecurityException("Unscoped bridge package")
            val callers = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
            if (packageName !in callers) throw SecurityException("Unexpected bridge caller")
            NativeBridgeRegistry.register(packageName, client)
        }
    }
    override fun onBind(intent: Intent?): IBinder = bridge
    override fun onDestroy() { NativeBridgeRegistry.clear(); super.onDestroy() }
}
