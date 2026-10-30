package dev.suryansh.airspeaker.core.raop

import dev.suryansh.airspeaker.core.rtsp.RtspServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Plays the part of an iPhone against the real RTSP server, connection handler and RTP receiver
 * over loopback sockets: Apple-Challenge, RSA-wrapped AES key, encrypted ALAC over UDP. The
 * decoded audio must match the source exactly. (Playback itself needs Android's AudioTrack and
 * is covered by AudioPlayerSimulationTest.)
 */
class RaopEndToEndTest {
    private val mac = byteArrayOf(0x1E, 0x2E, 0x5E, 0xB6.toByte(), 0x4A, 0x10)
    private val decoded = ByteArrayOutputStream()
    private val host = object : RaopHost {
        override val hardwareAddress = mac
        override fun claim(connection: RaopConnection) = Unit
        override fun release(connection: RaopConnection) = Unit
        override fun onSenderInfo(connection: RaopConnection, name: String?) = Unit
        override fun onOutputChanged(connection: RaopConnection, playing: Boolean) = Unit
        override fun onPaused(connection: RaopConnection) = Unit
        override fun onVolume(connection: RaopConnection, volume: Double) = Unit
        override fun currentVolume(): Double? = null
        override fun gain() = 0f
        override fun onParameters(connection: RaopConnection, contentType: String?, body: ByteArray) = Unit
        override fun decodedTap(): (ShortArray, Int) -> Unit = { pcm, frames ->
            synchronized(decoded) {
                for (i in 0 until frames * 2) {
                    decoded.write(pcm[i].toInt() and 0xFF)
                    decoded.write((pcm[i].toInt() shr 8) and 0xFF)
                }
            }
        }
    }
    private val server = RtspServer(0) { socket -> RaopConnection(socket, host).run() }

    @After
    fun tearDown() = server.close()

    @Test
    fun `encrypted ALAC session decodes bit-exact over real sockets`() {
        val key = RaopCrypto.airportKey
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent))
        val aesKey = Random(10).nextBytes(16)
        val aesIv = Random(11).nextBytes(16)
        val b64 = Base64.getEncoder().withoutPadding()

        Socket(InetAddress.getLoopbackAddress(), server.port).use { rtsp ->
            val input = rtsp.getInputStream()
            val output = rtsp.getOutputStream()
            var cseq = 0
            fun request(method: String, headers: Map<String, String> = emptyMap(), body: String = ""): Map<String, String> {
                val head = StringBuilder("$method rtsp://127.0.0.1/1 RTSP/1.0\r\nCSeq: ${++cseq}\r\nUser-Agent: AirPlay/560.13.1\r\n")
                headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                if (body.isNotEmpty()) head.append("Content-Length: ${body.toByteArray().size}\r\n")
                head.append("\r\n").append(body)
                output.write(head.toString().toByteArray())
                output.flush()
                return readResponse(input)
            }

            // OPTIONS + Apple-Challenge: the signature must verify against the public key.
            val challenge = Random(12).nextBytes(16)
            val options = request("OPTIONS", mapOf("Apple-Challenge" to b64.encodeToString(challenge)))
            assertEquals("200", options["status"])
            val signature = Base64.getDecoder().decode(options.getValue("Apple-Response"))
            val block = RaopCrypto.toFixedLength(BigInteger(1, signature).modPow(key.publicExponent, key.modulus), 256)
            val signed = (challenge + InetAddress.getLoopbackAddress().address + mac).copyOf(32)
            assertArrayEquals(signed, block.copyOfRange(256 - 32, 256))

            // ANNOUNCE with the AES key wrapped to the AirPort key, as iOS does.
            val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
                init(Cipher.ENCRYPT_MODE, publicKey)
                doFinal(aesKey)
            }
            val sdp = listOf(
                "v=0", "o=iTunes 1 0 IN IP4 127.0.0.1", "s=iTunes", "c=IN IP4 127.0.0.1", "t=0 0",
                "m=audio 0 RTP/AVP 96",
                "a=rtpmap:96 AppleLossless",
                "a=fmtp:96 4096 0 16 40 10 14 2 255 0 0 44100", // the fixture's frame length
                "a=rsaaeskey:${b64.encodeToString(wrapped)}",
                "a=aesiv:${b64.encodeToString(aesIv)}",
            ).joinToString("\r\n", postfix = "\r\n")
            assertEquals("200", request("ANNOUNCE", mapOf("Content-Type" to "application/sdp"), sdp)["status"])

            DatagramSocket().use { control ->
                DatagramSocket().use { timing ->
                    val setup = request(
                        "SETUP",
                        mapOf("Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${control.localPort};timing_port=${timing.localPort}"),
                    )
                    val audioPort = RaopConnection.parseTransport(setup.getValue("Transport")).getValue("server_port").toInt()
                    assertEquals("200", request("RECORD", mapOf("RTP-Info" to "seq=100;rtptime=0"))["status"])

                    // Encrypted ALAC packets over UDP.
                    val packets = resource("level2.packets")
                    val sizes = String(resource("level2.sizes")).lines().filter { it.isNotBlank() }.map { it.trim().toInt() }
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    DatagramSocket().use { audio ->
                        var offset = 0
                        for ((i, size) in sizes.withIndex()) {
                            val payload = packets.copyOfRange(offset, offset + size)
                            offset += size
                            val whole = size and 0xF.inv()
                            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
                            cipher.doFinal(payload, 0, whole, payload, 0)
                            val rtp = ByteArray(12)
                            rtp[0] = 0x80.toByte()
                            rtp[1] = if (i == 0) 0xE0.toByte() else 0x60
                            rtp[2] = ((100 + i) shr 8).toByte(); rtp[3] = (100 + i).toByte()
                            val ts = i * 4096
                            rtp[4] = (ts shr 24).toByte(); rtp[5] = (ts shr 16).toByte(); rtp[6] = (ts shr 8).toByte(); rtp[7] = ts.toByte()
                            val datagram = rtp + payload
                            audio.send(DatagramPacket(datagram, datagram.size, InetAddress.getLoopbackAddress(), audioPort))
                            Thread.sleep(2)
                        }
                    }

                    val expected = resource("source.pcm")
                    val deadline = System.currentTimeMillis() + 5_000
                    while (synchronized(decoded) { decoded.size() } < expected.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
                    assertArrayEquals(expected, synchronized(decoded) { decoded.toByteArray() })
                    assertEquals("200", request("TEARDOWN")["status"])
                }
            }
        }
    }

    private fun readResponse(input: InputStream): Map<String, String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            assertTrue("connection closed", c >= 0)
            if (c == '\n'.code) {
                val l = line.toString().trimEnd('\r')
                if (l.isEmpty()) break
                lines += l
                line.clear()
            } else {
                line.append(c.toChar())
            }
        }
        val headers = lines.drop(1).associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        headers["Content-Length"]?.toInt()?.let { n -> repeat(n) { input.read() } }
        return headers + ("status" to lines.first().split(' ')[1])
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/alac/$name")) { "missing fixture $name" }.readBytes()

}
