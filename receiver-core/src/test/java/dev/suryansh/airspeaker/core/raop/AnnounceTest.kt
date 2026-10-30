package dev.suryansh.airspeaker.core.raop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class AnnounceTest {
    private val iv = ByteArray(16) { it.toByte() }

    private fun sdp(vararg extra: String) = (listOf(
        "v=0",
        "o=iTunes 3413821438 0 IN IP4 192.168.29.20",
        "s=iTunes",
        "c=IN IP4 192.168.29.20",
        "t=0 0",
        "m=audio 0 RTP/AVP 96",
    ) + extra).joinToString("\r\n")

    @Test
    fun `encrypted ALAC announce from iOS`() {
        // Real senders omit base64 padding.
        val rsaKey = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(256) { 7 })
        val announce = Announce.parse(
            sdp(
                "a=rtpmap:96 AppleLossless",
                "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100",
                "a=rsaaeskey:$rsaKey",
                "a=aesiv:${Base64.getEncoder().withoutPadding().encodeToString(iv)}",
                "a=min-latency:11025",
                "a=max-latency:88200",
            ),
            unwrapKey = { ByteArray(16) { 1 } },
        )
        assertEquals(AudioCodec.ALAC, announce.codec)
        assertEquals(352, announce.framesPerPacket)
        assertArrayEquals(ByteArray(16) { 1 }, announce.aesKey)
        assertArrayEquals(iv, announce.aesIv)
        assertEquals(11025, announce.minLatency)
        assertEquals(88200, announce.maxLatency)
    }

    @Test
    fun `unencrypted PCM announce`() {
        val announce = Announce.parse(sdp("a=rtpmap:96 L16/44100/2"))
        assertEquals(AudioCodec.PCM, announce.codec)
        assertNull(announce.aesKey)
        assertEquals(false, announce.encrypted)
    }

    @Test
    fun `FairPlay is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            Announce.parse(sdp("a=rtpmap:96 AppleLossless", "a=fpaeskey:AAAA", "a=aesiv:AAAA"))
        }
    }

    @Test
    fun `transport and RTP-Info headers parse`() {
        val t = RaopConnection.parseTransport("RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002")
        assertEquals("6001", t["control_port"])
        assertEquals("6002", t["timing_port"])
        val info = RaopConnection.parseRtpInfo("seq=40130;rtptime=3762148226")
        assertEquals("3762148226", info["rtptime"])
        assertEquals(-532819070, info["rtptime"]!!.toLong().toInt()) // unsigned 32-bit wraps into Int
    }
}
