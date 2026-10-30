package dev.suryansh.airspeaker.core.meta

import dev.suryansh.airspeaker.core.dacp.DacpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class MetadataParsingTest {
    private fun item(tag: String, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag.toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf((value.size shr 24).toByte(), (value.size shr 16).toByte(), (value.size shr 8).toByte(), value.size.toByte()))
        out.write(value)
        return out.toByteArray()
    }

    private fun text(tag: String, value: String) = item(tag, value.toByteArray(Charsets.UTF_8))

    @Test
    fun `iOS-style mlit body yields title, artist, album and duration`() {
        val body = item(
            "mlit",
            item("mikd", byteArrayOf(2)) +
                text("minm", "Hôtel Costes — Late Night") +
                text("asar", "Stéphane Pompougnac") +
                text("asal", "Hôtel Costes 7") +
                item("astm", byteArrayOf(0, 3, 0xA9.toByte(), 0x80.toByte())) + // 240000 ms
                item("asdk", byteArrayOf(0)) +
                item("mper", ByteArray(8) { 1 }) +
                item("xyzw", ByteArray(20)), // unknown and long: kept as bytes, ignored
        )
        val track = TrackInfo.from(Dmap.parse(body))
        assertEquals("Hôtel Costes — Late Night", track.title)
        assertEquals("Stéphane Pompougnac", track.artist)
        assertEquals("Hôtel Costes 7", track.album)
        assertEquals(240_000L, track.durationMs)
    }

    @Test
    fun `truncated DMAP keeps what parsed and does not throw`() {
        val body = text("minm", "Song") + text("asar", "Artist").copyOf(10)
        val track = TrackInfo.from(Dmap.parse(body))
        assertEquals("Song", track.title)
        assertNull(track.artist)
    }

    @Test
    fun `progress converts RTP times to milliseconds`() {
        val p = Progress.parse(" 1146221540/1146549156/1195701740")!!
        assertEquals((1146549156L - 1146221540L) * 1000 / 44100, p.positionMs)
        assertEquals((1195701740L - 1146221540L) * 1000 / 44100, p.durationMs)
    }

    @Test
    fun `progress survives RTP wrap-around`() {
        val start = 4294960000L
        val p = Progress.parse("$start/${(start + 44100) and 0xFFFFFFFFL}/${(start + 441000) and 0xFFFFFFFFL}")!!
        assertEquals(1000L, p.positionMs)
        assertEquals(10_000L, p.durationMs)
        assertNull(Progress.parse("garbage"))
    }

    @Test
    fun `DACP service name and request`() {
        assertEquals("iTunes_Ctrl_A1B2C3D4E5F60718", DacpClient.serviceName("A1B2C3D4E5F60718"))
        assertEquals(
            "GET /ctrl-int/1/nextitem HTTP/1.1\r\nHost: 192.168.29.20:3689\r\nActive-Remote: 123\r\nConnection: close\r\n\r\n",
            DacpClient.requestText(InetAddress.getByName("192.168.29.20"), 3689, "nextitem", "123"),
        )
        // iPhones connect over IPv6 link-local; the zone id stays out of the Host header.
        val scoped = java.net.Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
        assertTrue(DacpClient.requestText(scoped, 3689, "playpause", "1").contains("Host: [fe80:0:0:0:0:0:0:1]:3689\r\n"))
    }

    @Test
    fun `DACP response with and without Content-Length`() {
        val r = DacpClient.readResponse("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcEXTRA".byteInputStream())
        assertEquals(200, r.status)
        assertEquals("abc", String(r.body))
        val noContent = DacpClient.readResponse("HTTP/1.1 204 No Content\r\nDAAP-Server: iTunes\r\n\r\n".byteInputStream())
        assertEquals(204, noContent.status)
        assertEquals(0, noContent.body.size)
    }
}
