package dev.suryansh.airspeaker.core.dacp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.suryansh.airspeaker.core.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Remote control of the sender (play/pause, next, previous) over DACP. The sender advertises
 * `_dacp._tcp` as `iTunes_Ctrl_<DACP-ID>`; requests must carry its `Active-Remote` token.
 */
class DacpClient(
    context: Context,
    val dacpId: String,
    private val activeRemote: String,
    private val sender: InetAddress,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var port: Int? = null

    enum class Command(val path: String) {
        PlayPause("playpause"),
        Next("nextitem"),
        Previous("previtem"),
        VolumeUp("volumeup"),
        VolumeDown("volumedown"),
    }

    /** Sends [command]; returns the HTTP status, or null if the sender's DACP service can't be found. */
    suspend fun send(command: Command): Int? {
        val p = port ?: discoverPort()?.also { port = it } ?: run {
            Logs.meta.w("DACP: no ${serviceName(dacpId)} on the network")
            return null
        }
        return withContext(Dispatchers.IO) {
            val connection = URL(requestUrl(sender, p, command)).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.setRequestProperty("Active-Remote", activeRemote)
                connection.responseCode.also { Logs.meta.i("DACP ${command.path} → $it") }
            } catch (e: Exception) {
                Logs.meta.w("DACP ${command.path} failed: ${e.message}")
                port = null // the sender may have moved; rediscover next time
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    @Suppress("DEPRECATION") // resolveService is the API available back to minSdk 31.
    private suspend fun discoverPort(): Int? {
        val found = CompletableDeferred<NsdServiceInfo>()
        val resolved = CompletableDeferred<Int>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.equals(serviceName(dacpId), ignoreCase = true)) found.complete(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                found.completeExceptionally(IllegalStateException("discovery failed $error"))
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            val info = withTimeoutOrNull(5_000) { runCatching { found.await() }.getOrNull() } ?: return null
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    resolved.complete(resolvedInfo.port)
                }
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    resolved.completeExceptionally(IllegalStateException("resolve failed $error"))
                }
            })
            return withTimeoutOrNull(5_000) { runCatching { resolved.await() }.getOrNull() }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_dacp._tcp"

        fun serviceName(dacpId: String) = "iTunes_Ctrl_$dacpId"

        fun requestUrl(host: InetAddress, port: Int, command: Command): String {
            // IPv6 literals go in brackets, with any zone id's '%' escaped.
            val h = host.hostAddress?.let { if (':' in it) "[${it.replace("%", "%25")}]" else it }
            return "http://$h:$port/ctrl-int/1/${command.path}"
        }
    }
}
