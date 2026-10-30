package dev.suryansh.airspeaker.core.raop

import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.audio.AudioPlayer
import dev.suryansh.airspeaker.core.rtsp.Rtsp
import dev.suryansh.airspeaker.core.rtsp.RtspProtocolException
import dev.suryansh.airspeaker.core.rtsp.RtspRequest
import dev.suryansh.airspeaker.core.rtsp.RtspResponse
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale

/** What a connection needs from the engine. */
interface RaopHost {
    val hardwareAddress: ByteArray

    /** Makes [connection] the one sender we play; any other sender is cut off. */
    fun claim(connection: RaopConnection)
    fun release(connection: RaopConnection)
    fun onSenderInfo(connection: RaopConnection, name: String?)
    fun onOutputChanged(connection: RaopConnection, playing: Boolean)
    fun onPaused(connection: RaopConnection)
    fun onVolume(connection: RaopConnection, volume: Double)
    fun currentVolume(): Double?
    fun gain(): Float
    fun latencyOffsetMs(): Int = 0
    fun onParameters(connection: RaopConnection, contentType: String?, body: ByteArray)

    /** RECORD: the sender is about to start streaming. */
    fun onRecord(connection: RaopConnection) = Unit

    /** Debug hook for decoded audio; null in normal use. */
    fun decodedTap(): ((pcm: ShortArray, frames: Int) -> Unit)? = null
}

/** One RTSP control connection from a sender. Runs on its own thread until the sender hangs up. */
class RaopConnection(private val socket: Socket, private val host: RaopHost) {
    private var announce: Announce? = null
    @Volatile private var session: PlaybackSession? = null
    private var airPlayVersion = 0
    @Volatile private var terminated = false
    @Volatile private var lastRequestAtNs = System.nanoTime()

    var dacpId: String? = null
        private set
    var activeRemote: String? = null
        private set
    val senderAddress get() = socket.inetAddress

    fun run() {
        Logs.rtsp.i("connection from ${socket.inetAddress.hostAddress}")
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            while (!terminated) {
                val request = try {
                    Rtsp.readRequest(input) ?: break
                } catch (e: SocketTimeoutException) {
                    if (isAbandoned()) {
                        Logs.rtsp.w("no traffic from ${socket.inetAddress.hostAddress} for ${SESSION_TIMEOUT_NS / 1_000_000_000} s; dropping the session")
                        break
                    }
                    continue
                }
                lastRequestAtNs = System.nanoTime()
                val response = try {
                    handle(request)
                } catch (e: IllegalArgumentException) {
                    Logs.rtsp.w("rejected $request: ${e.message}")
                    RtspResponse().error(400, "Bad Request")
                }
                request.cseq?.let { response.headers["CSeq"] = it }
                response.headers["Server"] = "AirTunes/105.1"
                Rtsp.writeResponse(output, response)
            }
        } catch (e: IOException) {
            if (!terminated) Logs.rtsp.i("connection closed: ${e.message}")
        } catch (e: RtspProtocolException) {
            Logs.rtsp.w("protocol error: ${e.message}")
        } catch (e: Exception) {
            // One misbehaving sender must never take the receiver down with it.
            Logs.rtsp.e("connection failed", e)
        } finally {
            endSession()
            try {
                socket.close()
            } catch (_: IOException) {
            }
            host.release(this)
            Logs.rtsp.i("connection from ${socket.inetAddress.hostAddress} ended")
        }
    }

    /** Called when another sender takes over. */
    fun terminate() {
        terminated = true
        endSession()
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    fun setGain(gain: Float) {
        session?.setGain(gain)
    }

    fun setLatencyOffsetMs(ms: Int) {
        session?.setLatencyOffsetMs(ms)
    }

    /** Neither RTSP requests nor RTP packets for [SESSION_TIMEOUT_NS]: the sender is gone. */
    private fun isAbandoned(): Boolean {
        val now = System.nanoTime()
        val lastPacket = session?.lastPacketAtNs ?: 0L
        return now - maxOf(lastRequestAtNs, lastPacket) > SESSION_TIMEOUT_NS
    }

    private fun handle(req: RtspRequest): RtspResponse {
        Logs.rtsp.d("${req.method} ${req.uri}")
        val response = RtspResponse()
        req.header("Apple-Challenge")?.let { response.header("Apple-Response", appleResponse(it)) }
        req.header("DACP-ID")?.let { dacpId = it }
        req.header("Active-Remote")?.let { activeRemote = it }
        req.header("User-Agent")?.let { ua ->
            Regex("AirPlay/(\\d+)").find(ua)?.let { airPlayVersion = it.groupValues[1].toInt() }
        }

        when (req.method) {
            "OPTIONS" -> response.header(
                "Public",
                "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER",
            )

            "ANNOUNCE" -> {
                val parsed = Announce.parse(req.bodyText)
                host.claim(this)
                endSession()
                announce = parsed
                host.onSenderInfo(this, req.header("X-Apple-Client-Name"))
                Logs.rtsp.i("ANNOUNCE ${parsed.codec} ${parsed.framesPerPacket} frames/packet, encrypted=${parsed.encrypted}, AirPlay/$airPlayVersion")
            }

            "SETUP" -> {
                val announce = this.announce ?: return response.error(455, "Method Not Valid In This State")
                val transport = parseTransport(req.header("Transport") ?: "")
                endSession()
                val s = PlaybackSession(
                    announce = announce,
                    sender = socket.inetAddress,
                    senderControlPort = transport["control_port"]?.toIntOrNull() ?: 0,
                    senderTimingPort = transport["timing_port"]?.toIntOrNull() ?: 0,
                    addFixedLatency = { flags -> flags == 7 || airPlayVersion in 1..353 || airPlayVersion >= 371 },
                    listener = object : AudioPlayer.Listener {
                        override fun onOutputStarted() = host.onOutputChanged(this@RaopConnection, true)
                        override fun onOutputStopped() = host.onOutputChanged(this@RaopConnection, false)
                    },
                    decodedTap = host.decodedTap(),
                )
                s.setGain(host.gain())
                s.setLatencyOffsetMs(host.latencyOffsetMs())
                s.startReceiving()
                session = s
                response.header(
                    "Transport",
                    "RTP/AVP/UDP;unicast;mode=record;server_port=${s.rtp.audioPort};" +
                        "control_port=${s.rtp.controlPort};timing_port=${s.rtp.timingPort}",
                )
                response.header("Session", "1")
                response.header("Audio-Jack-Status", "connected; type=analog")
            }

            "RECORD" -> {
                val s = session ?: return response.error(455, "Method Not Valid In This State")
                s.startPlaying()
                host.onRecord(this)
                response.header("Audio-Latency", "11025")
            }

            "FLUSH" -> {
                val rtpTime = parseRtpInfo(req.header("RTP-Info"))["rtptime"]?.toLongOrNull()?.toInt()
                session?.flush(rtpTime)
                host.onPaused(this)
                Logs.rtsp.i("FLUSH until rtptime=$rtpTime")
            }

            "TEARDOWN" -> {
                endSession()
                response.header("Connection", "close")
                terminated = true
            }

            "SET_PARAMETER" -> setParameter(req)

            "GET_PARAMETER" -> {
                if (req.bodyText.contains("volume")) {
                    response.text("text/parameters", String.format(Locale.US, "volume: %.6f\r\n", host.currentVolume() ?: -15.0))
                }
            }

            "POST" -> if (req.uri != "/feedback") response.error(404, "Not Found")

            else -> response.error(501, "Not Implemented")
        }
        return response
    }

    private fun setParameter(req: RtspRequest) {
        if (req.contentType == "text/parameters") {
            for (line in req.bodyText.lineSequence()) {
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim()
                if (key == "volume") value.toDoubleOrNull()?.let { host.onVolume(this, it) }
            }
        }
        host.onParameters(this, req.contentType, req.body)
    }

    @Synchronized
    private fun endSession() {
        session?.let {
            session = null
            it.close()
        }
    }

    private fun appleResponse(challenge: String): String {
        val bytes = Base64.getDecoder().decode(challenge.trim().trimEnd('='))
        val signature = RaopCrypto.appleResponse(bytes, socket.localAddress, host.hardwareAddress)
        return Base64.getEncoder().withoutPadding().encodeToString(signature)
    }

    companion object {
        private const val READ_TIMEOUT_MS = 15_000
        private const val SESSION_TIMEOUT_NS = 120_000_000_000L

        /** `RTP/AVP/UDP;unicast;control_port=6001;timing_port=6002` to a map. */
        fun parseTransport(header: String): Map<String, String> = header.split(';').mapNotNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
        }.toMap()

        /** `seq=1234;rtptime=5678` to a map. */
        fun parseRtpInfo(header: String?): Map<String, String> = parseTransport(header ?: "")
    }
}
