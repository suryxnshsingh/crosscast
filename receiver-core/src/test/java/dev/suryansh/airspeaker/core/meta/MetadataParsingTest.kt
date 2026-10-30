package dev.suryansh.airspeaker.core.meta

import dev.suryansh.airspeaker.core.dacp.DacpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            "http://192.168.29.20:3689/ctrl-int/1/nextitem",
            DacpClient.requestUrl(InetAddress.getByName("192.168.29.20"), 3689, DacpClient.Command.Next),
        )
        assertEquals(
            "http://[fe80:0:0:0:0:0:0:1]:3689/ctrl-int/1/playpause",
            DacpClient.requestUrl(InetAddress.getByName("fe80::1"), 3689, DacpClient.Command.PlayPause),
        )
    }
}
