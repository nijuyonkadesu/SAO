package one.njk.sao.floating

import one.njk.sao.immich.ImmichConnection
import java.security.MessageDigest

data class ImmichConfig(val serverUrl: String, val apiKey: String, val smartSearch: Boolean = true,
    val albumId: String? = null) : SourceConfiguration {
    override val kind = "immich"
    val connection get() = ImmichConnection(serverUrl, apiKey)
    // Account/key scope + settings scope: never reuse another account's assets or result snapshots.
    override val identity by lazy { digest("${connection.apiUrl}|$apiKey|$smartSearch|$albumId") }
    override fun toString() = "ImmichConfig(serverUrl=$serverUrl, apiKey=<redacted>, smartSearch=$smartSearch, albumId=$albumId)"
}
internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
