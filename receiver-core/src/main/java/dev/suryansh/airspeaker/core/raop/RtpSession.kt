package dev.suryansh.airspeaker.core.raop

import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.audio.AlacDecoder
import dev.suryansh.airspeaker.core.audio.AlacException
import dev.suryansh.airspeaker.core.audio.ClockSync
import dev.suryansh.airspeaker.core.audio.JitterBuffer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * The three UDP channels of a classic AirPlay session:
 *
 * - **audio**: RTP packets (payload type 96), AES-CBC encrypted ALAC or plain L16.
 * - **control**: sync packets (0xD4) from the sender, resend requests (0xD5) from us and
 *   resent audio (0xD6) back from the sender.
 * - **timing**: NTP-like exchanges (0xD2 request / 0xD3 reply) used to estimate the clock offset.
 */
class RtpSession(
    private val sender: InetAddress,
    private val senderControlPort: Int,
    private val senderTimingPort: Int,
    private val announce: Announce,
    private val buffer: JitterBuffer,
    private val clock: ClockSync,
    /** Whether sync packets need Shairport's extra 11025 frames (depends on the sender version). */
    private val addFixedLatency: (flags: Int) -> Boolean,
    /** Debug hook: sees every decoded packet in arrival order. */
    private val decodedTap: ((pcm: ShortArray, frames: Int) -> Unit)? = null,
) : Closeable {
    private val audioSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    private val timingSocket = DatagramSocket(0)

    val audioPort: Int get() = audioSocket.localPort
    val controlPort: Int get() = controlSocket.localPort
    val timingPort: Int get() = timingSocket.localPort

    private val decryptor = if (announce.encrypted) AudioDecryptor(announce.aesKey!!, announce.aesIv!!) else null
    private val decoder = if (announce.codec == AudioCodec.ALAC) AlacDecoder(announce.alac) else null
    private val pcm = ShortArray(announce.framesPerPacket * 2)

    @Volatile private var closed = false
    private var expectedSeq = -1
    private var resendSeq = 0

    /** Packets with RTP time before this are from before the last FLUSH and are dropped. */
    @Volatile private var flushRtp: Int? = null

    private val threads = mutableListOf<Thread>()

    fun start() {
        threads += thread(name = "rtp-audio") { receiveLoop(audioSocket) { p, len -> onAudio(p, 0, len) } }
        threads += thread(name = "rtp-control") { receiveLoop(controlSocket, ::onControl) }
        threads += thread(name = "rtp-timing") { receiveLoop(timingSocket, ::onTiming) }
        threads += thread(name = "rtp-timing-requests") { requestTimingLoop() }
        Logs.rtsp.i("RTP ports audio=$audioPort control=$controlPort timing=$timingPort, sender control=$senderControlPort timing=$senderTimingPort")
    }

    /** Drops everything up to [rtp] (from FLUSH's RTP-Info); later packets start a new run. */
    fun flush(rtp: Int?) {
        flushRtp = rtp
        synchronized(this) { expectedSeq = -1 }
    }

    override fun close() {
        closed = true
        audioSocket.close()
        controlSocket.close()
        timingSocket.close()
        threads.forEach { it.interrupt() }
    }

    // ---- Receiving ---------------------------------------------------------------------------

    private inline fun receiveLoop(socket: DatagramSocket, handle: (ByteArray, Int) -> Unit) {
        val bytes = ByteArray(MAX_PACKET)
        val packet = DatagramPacket(bytes, bytes.size)
        while (!closed) {
            try {
                packet.setData(bytes, 0, bytes.size)
                socket.receive(packet)
                handle(bytes, packet.length)
            } catch (e: SocketException) {
                if (!closed) Logs.rtsp.w("UDP socket failed", e)
                return
            } catch (e: Exception) {
                Logs.audio.w("dropped bad packet", e)
            }
        }
    }

    @Synchronized
    private fun onAudio(p: ByteArray, offset: Int, length: Int) {
        if (length < 12 || (p[offset + 1].toInt() and 0x7F) != 0x60) return
        val seq = u16(p, offset + 2)
        val rtp = s32(p, offset + 4)
        flushRtp?.let {
            if (rtp - it < 0) return
            flushRtp = null // the new run has started; RTP times may legitimately restart later
        }

        val payload = offset + 12
        val payloadLength = length - 12
        decryptor?.decrypt(p, payload, payloadLength)
        val frames = try {
            decodeInto(p, payload, payloadLength)
        } catch (e: AlacException) {
            Logs.audio.w("undecodable packet seq=$seq: ${e.message}")
            return
        }
        buffer.put(seq, rtp, pcm, frames)
        decodedTap?.invoke(pcm, frames)
        trackSequence(seq)
    }

    private fun decodeInto(p: ByteArray, offset: Int, length: Int): Int {
        if (decoder != null) return decoder.decode(p, offset, length, pcm)
        // L16: big-endian interleaved stereo.
        val frames = minOf(length / 4, announce.framesPerPacket)
        for (i in 0 until frames * 2) pcm[i] = ((p[offset + i * 2].toInt() shl 8) or (p[offset + i * 2 + 1].toInt() and 0xFF)).toShort()
        return frames
    }

    private fun trackSequence(seq: Int) {
        if (expectedSeq < 0) {
            expectedSeq = (seq + 1) and 0xFFFF
            return
        }
        val gap = ((seq - expectedSeq).toShort()).toInt()
        when {
            gap == 0 -> expectedSeq = (seq + 1) and 0xFFFF
            gap > 0 -> {
                if (gap < MAX_RESEND) requestResend(expectedSeq, gap)
                expectedSeq = (seq + 1) and 0xFFFF
            }
            else -> Unit // a late or resent packet; it is already in the buffer
        }
    }

    private fun onControl(p: ByteArray, length: Int) {
        when (p[1].toInt() and 0xFF) {
            0xD4 -> if (length >= 20) {
                val flags = u16(p, 2)
                val rtpLessLatency = s32(p, 4)
                val remoteNs = ClockSync.ntpToNanos(u32(p, 8), u32(p, 12))
                val syncRtp = s32(p, 16)
                clock.applySync(syncRtp, rtpLessLatency, remoteNs, addFixedLatency(flags), System.nanoTime())
            }
            0xD6 -> if (length > 4 + 12) onAudio(p, 4, length - 4)
        }
    }

    private fun requestResend(firstSeq: Int, count: Int) {
        val req = ByteArray(8)
        req[0] = 0x80.toByte()
        req[1] = 0xD5.toByte()
        putU16(req, 2, resendSeq++ and 0xFFFF)
        putU16(req, 4, firstSeq)
        putU16(req, 6, count)
        send(controlSocket, req, senderControlPort)
        Logs.audio.d("requested resend of $count packet(s) from seq $firstSeq")
    }

    // ---- Timing ------------------------------------------------------------------------------

    private fun onTiming(p: ByteArray, length: Int) {
        if (length < 32) return
        val now = System.nanoTime()
        when (p[1].toInt() and 0xFF) {
            0xD3 -> clock.addTimingSample(
                t1LocalNs = ClockSync.ntpToNanos(u32(p, 8), u32(p, 12)),
                t2RemoteNs = ClockSync.ntpToNanos(u32(p, 16), u32(p, 20)),
                t3RemoteNs = ClockSync.ntpToNanos(u32(p, 24), u32(p, 28)),
                t4LocalNs = now,
            )
            0xD2 -> { // some senders ask us too; answer with our clock
                val reply = ByteArray(32)
                reply[0] = 0x80.toByte()
                reply[1] = 0xD3.toByte()
                reply[3] = 0x07
                p.copyInto(reply, 8, 24, 32) // origin = their transmit time
                putNtp(reply, 16, now)
                putNtp(reply, 24, System.nanoTime())
                send(timingSocket, reply, senderTimingPort)
            }
        }
    }

    private fun requestTimingLoop() {
        var sent = 0
        while (!closed) {
            val req = ByteArray(32)
            req[0] = 0x80.toByte()
            req[1] = 0xD2.toByte()
            req[3] = 0x07
            putNtp(req, 24, System.nanoTime())
            send(timingSocket, req, senderTimingPort)
            sent++
            // A quick burst to get a good estimate early, then every 3 s.
            try {
                Thread.sleep(if (sent < 4) 100 else 3_000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun send(socket: DatagramSocket, data: ByteArray, port: Int) {
        if (closed || port <= 0) return
        try {
            socket.send(DatagramPacket(data, data.size, sender, port))
        } catch (e: Exception) {
            if (!closed) Logs.rtsp.w("UDP send to $sender:$port failed: ${e.message}")
        }
    }

    private companion object {
        const val MAX_RESEND = 128

        /** iOS sends ~1.4 KB packets (352 frames); leave room for senders using larger frames. */
        const val MAX_PACKET = 16 * 1024

        fun u16(p: ByteArray, i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        fun s32(p: ByteArray, i: Int) = (u16(p, i) shl 16) or u16(p, i + 2)
        fun u32(p: ByteArray, i: Int) = s32(p, i).toLong() and 0xFFFFFFFFL

        fun putU16(p: ByteArray, i: Int, v: Int) {
            p[i] = (v shr 8).toByte()
            p[i + 1] = v.toByte()
        }

        fun putU32(p: ByteArray, i: Int, v: Long) {
            putU16(p, i, (v shr 16).toInt() and 0xFFFF)
            putU16(p, i + 2, v.toInt() and 0xFFFF)
        }

        fun putNtp(p: ByteArray, i: Int, nanos: Long) {
            val (seconds, fraction) = ClockSync.nanosToNtp(nanos)
            putU32(p, i, seconds)
            putU32(p, i + 4, fraction)
        }
    }
}
