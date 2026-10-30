package one.njk.sao.importing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Network
import kotlinx.coroutines.*

/** Job constraints own waiting. These checks run only during an actual transfer, never while idle. */
internal object UploadNetworkPolicy {
    data class Connection(val internet: Boolean,val wifi: Boolean,val cellular: Boolean,val unmetered: Boolean)
    fun request(wifiOnly: Boolean,automatic: Boolean)=NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .apply {
            if(wifiOnly) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            if(automatic) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.build()

    fun canUse(connection: Connection?,wifiOnly: Boolean,automatic: Boolean): Boolean =
        connection!=null && connection.internet && (!automatic || connection.unmetered) &&
            (!wifiOnly || connection.wifi && !connection.cellular)

    fun allows(capabilities: NetworkCapabilities?,wifiOnly: Boolean,automatic: Boolean)=canUse(capabilities?.let {
        Connection(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    },wifiOnly,automatic)

    fun allows(context: Context,wifiOnly: Boolean,automatic: Boolean): Boolean {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        return allows(manager.getNetworkCapabilities(manager.activeNetwork),wifiOnly,automatic)
    }
    private class ConnectionLost: CancellationException("Upload is waiting for an allowed network")
    /** A single default-route callback exists only for the duration of an active upload batch. */
    suspend fun run(context: Context,network: Boolean,wifiOnly: Boolean,automatic: Boolean,block: suspend ()->Boolean): Boolean {
        if(!network) return block()
        if(!allows(context,wifiOnly,automatic)) return true
        return supervisorScope {
            val transfer=async(start=CoroutineStart.LAZY) { block() }
            val manager=context.getSystemService(ConnectivityManager::class.java)
            val callback=object: ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) {
                    if(!allows(capabilities,wifiOnly,automatic)) transfer.cancel(ConnectionLost())
                }
                override fun onLost(network: Network) {
                    if(!allows(context,wifiOnly,automatic)) transfer.cancel(ConnectionLost())
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            try { transfer.start(); transfer.await() }
            catch(_: ConnectionLost) { true }
            finally { manager.unregisterNetworkCallback(callback) }
        }
    }
}
