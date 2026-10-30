package dev.suryansh.airspeaker.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dev.suryansh.airspeaker.core.Logs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

/** The default network as seen by the receiver: `null` while offline. */
data class LocalNetwork(val network: Network, val ipv4: Inet4Address?, val interfaceName: String?)

/**
 * Emits the current default network and every change to it: a different network, a new IPv4
 * address, or going offline. mDNS registration is redone whenever this emits.
 */
fun networkChanges(context: Context): Flow<LocalNetwork?> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Logs.mdns.i("network available: $network")
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val ipv4 = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
            trySend(LocalNetwork(network, ipv4, lp.interfaceName))
        }

        override fun onLost(network: Network) {
            Logs.mdns.w("network lost: $network")
            trySend(null)
        }
    }
    cm.registerDefaultNetworkCallback(callback)
    if (cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
    ) {
        trySend(null)
    }
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
