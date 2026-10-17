package one.njk.sao.immichprovider

import one.njk.sao.source.*

import one.njk.sao.immich.ImmichConnection

data class ImmichConfig(val serverUrl: String, val apiKey: String, val smartSearch: Boolean = true,
    val albumId: String? = null) {
    val connection get() = ImmichConnection(serverUrl, apiKey)
    // Account/key scope + settings scope: never reuse another account's assets or result snapshots.
    val identity by lazy { digest("${connection.apiUrl}|$apiKey|$smartSearch|$albumId") }
    override fun toString() = "ImmichConfig(serverUrl=$serverUrl, apiKey=<redacted>, smartSearch=$smartSearch, albumId=$albumId)"
}
