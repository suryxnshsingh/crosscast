package dev.suryansh.airspeaker.core.dacp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.suryansh.airspeaker.core.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Remote control of the sender (play/pause, next, previous) over DACP. The sender advertises
 * `_dacp._tcp` as `iTunes_Ctrl_<DACP-ID>`; requests must carry its `Active-Remote` token.
 *
 * Requests are plain HTTP/1.1 written to a socket: iPhones connect from IPv6 link-local
 * addresses (`fe80::…%wlan0`), which a socket handles but Android's URL parser rejects.
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

    class Response(val status: Int, val body: ByteArray)

    /** Sends [command]; returns the HTTP status, or null if the sender's DACP service can't be reached. */
    suspend fun send(command: Command): Int? = request(command.path)?.status

    /** `GET /ctrl-int/1/<path>`, e.g. `playpause` or `getproperty?properties=dmcp.volume`. */
    suspend fun request(path: String): Response? {
        val p = port ?: discoverPort()?.also { port = it } ?: run {
            Logs.meta.w("DACP: no ${serviceName(dacpId)} on the network")
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(sender, p), TIMEOUT_MS)
                    socket.soTimeout = TIMEOUT_MS
                    socket.getOutputStream().apply {
                        write(requestText(sender, p, path, activeRemote).toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    readResponse(socket.getInputStream())
                }.also { Logs.meta.i("DACP $path → ${it.status} (${it.body.size} bytes)") }
            } catch (e: Exception) {
                Logs.meta.w("DACP $path failed: ${e.message}")
                port = null // the sender may have moved; rediscover next time
                null
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

        private const val TIMEOUT_MS = 3_000

        fun requestText(host: InetAddress, port: Int, path: String, activeRemote: String): String {
            // IPv6 literals go in brackets; a zone id (%wlan0) means nothing to the other end.
            val h = host.hostAddress!!.substringBefore('%').let { if (':' in it) "[$it]" else it }
            return "GET /ctrl-int/1/$path HTTP/1.1\r\n" +
                "Host: $h:$port\r\n" +
                "Active-Remote: $activeRemote\r\n" +
                "Connection: close\r\n\r\n"
        }

        /** Status line, headers, then a Content-Length body (or everything until the sender closes). */
        fun readResponse(input: InputStream): Response {
            fun line(): String? {
                val out = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
                    if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                    out.write(b)
                }
            }
            val status = line()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("no HTTP status line")
            var length = -1
            while (true) {
                val header = line() ?: break
                if (header.isEmpty()) break
                if (header.substringBefore(':').trim().equals("Content-Length", ignoreCase = true)) {
                    length = header.substringAfter(':').trim().toIntOrNull() ?: -1
                }
            }
            val body = when {
                status == 204 || status == 304 -> ByteArray(0)
                length >= 0 -> input.readNBytes(length)
                else -> readUntilClosed(input)
            }
            return Response(status, body)
        }

        /** A sender that keeps the connection open anyway just ends the body with a read timeout. */
        private fun readUntilClosed(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // keep what arrived
            }
            return out.toByteArray()
        }
    }
}
