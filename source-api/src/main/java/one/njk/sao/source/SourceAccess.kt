package one.njk.sao.source

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import java.security.MessageDigest

/** Current signers only: a user approval does not silently follow a signing-key change. */
object SourceSignatures {
    fun identity(context: Context, packageName: String): String {
        val info=context.packageManager.getPackageInfo(packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures=requireNotNull(info.signingInfo).apkContentsSigners
        require(signatures.isNotEmpty())
        return signatures.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) } }.sorted().joinToString(",")
    }
    fun samePublisher(context: Context, packageName: String) =
        context.packageManager.checkSignatures(context.packageName,packageName)==PackageManager.SIGNATURE_MATCH
}

/** Shared handshake for sources signed independently of the installed host. */
object SourceAccess {
    val hosts=setOf("one.njk.sao","one.njk.sao.testing")
    const val PERMISSION="one.njk.sao.permission.SOURCE_EXTENSION"
    const val AUTHORITY_SUFFIX=".source-trust"
    const val AUTHORIZE="authorize"
    const val ALLOWED="allowed"

    /** Settings callers must be an actual host holding the host's signature permission. Run on IO. */
    fun isHost(context: Context, packageName: String?): Boolean {
        if(packageName !in hosts) return false
        val host=requireNotNull(packageName)
        if(SourceSignatures.samePublisher(context,host)) return true
        return try {
            val manager=context.packageManager
            val owner=manager.getPermissionInfo(PERMISSION,0).packageName
            if(owner !in hosts || manager.checkSignatures(owner,host)!=PackageManager.SIGNATURE_MATCH ||
                manager.checkPermission(PERMISSION,host)!=PackageManager.PERMISSION_GRANTED) return false
            authorized(context,host,context.packageName)
        } catch(_: Exception) { false }
    }
    fun caller(context: Context, uid: Int): String? = context.packageManager.getPackagesForUid(uid).orEmpty()
        .firstOrNull { it==context.packageName || isHost(context,it) }

    /** Enforced before binding, even when a host caller bypasses its higher-level adapter. */
    fun requireTarget(context: Context, packageName: String) {
        if(context.packageName !in hosts || SourceSignatures.samePublisher(context,packageName)) return
        if(!authorized(context,context.packageName,packageName)) throw ExtensionFailure("UNTRUSTED")
    }
    private fun authorized(context: Context, host: String, source: String): Boolean = try {
        val authority=host+AUTHORITY_SUFFIX
        if(context.packageManager.resolveContentProvider(authority,0)?.packageName!=host) false else {
            // A nested Binder call must represent this process, not its original caller.
            val token=Binder.clearCallingIdentity()
            try { context.contentResolver.call(Uri.parse("content://$authority"),AUTHORIZE,source,null)?.getBoolean(ALLOWED)==true }
            finally { Binder.restoreCallingIdentity(token) }
        }
    } catch(_: Exception) { false }
}
