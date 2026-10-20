package one.njk.sao.extension.findthatmeme

import android.content.Context
import one.njk.sao.source.ExtensionFailure

/** A single lazy HTTP pool; no result/media cache, polling, service, or scheduled work. */
internal class FindThatMemeRuntime private constructor(context: Context) {
    val profiles = FindThatMemeProfiles(context)
    private val production by lazy { FindThatMemeApi() }
    internal var fixture: FindThatMemeApi? = null
    val api get() = fixture ?: production

    companion object {
        @Volatile private var instance: FindThatMemeRuntime? = null
        fun get(context: Context): FindThatMemeRuntime = instance ?: synchronized(this) {
            instance ?: FindThatMemeRuntime(context.applicationContext).also { instance = it }
        }
        fun failure(error: Exception): ExtensionFailure = when (error) {
            is ExtensionFailure -> error
            is org.json.JSONException -> ExtensionFailure("INVALID_RESPONSE")
            is javax.net.ssl.SSLException -> ExtensionFailure("TLS")
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> ExtensionFailure("TIMEOUT")
            is java.io.IOException -> ExtensionFailure("NETWORK")
            else -> ExtensionFailure("REQUEST_FAILED")
        }
    }
}
