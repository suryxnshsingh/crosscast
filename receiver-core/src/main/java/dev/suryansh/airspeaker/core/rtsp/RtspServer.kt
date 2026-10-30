package dev.suryansh.airspeaker.core.rtsp

import dev.suryansh.airspeaker.core.Logs
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/** Accepts RTSP connections and hands each one to [onConnection] on its own thread. */
class RtspServer(preferredPort: Int, private val onConnection: (Socket) -> Unit) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        try {
            bind(InetSocketAddress(preferredPort))
        } catch (e: SocketException) {
            Logs.rtsp.w("port $preferredPort is busy; using a free port", e)
            bind(InetSocketAddress(0))
        }
    }

    val port: Int = server.localPort

    init {
        thread(name = "rtsp-accept") {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    thread(name = "rtsp-${socket.inetAddress.hostAddress}") { onConnection(socket) }
                } catch (e: SocketException) {
                    if (!server.isClosed) Logs.rtsp.e("accept failed", e)
                }
            }
        }
        Logs.rtsp.i("RTSP listening on port $port")
    }

    override fun close() = server.close()
}
