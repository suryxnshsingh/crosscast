package dev.suryansh.crosscast.core.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RtspMessageTest {
    @Test
    fun `reads pipelined requests with binary bodies`() {
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x0D, 0x0A, 0x0D, 0x0A, 0x00)
        val stream = ByteArrayInputStream(
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\napple-challenge: abc\r\n\r\n".toByteArray() +
                "SET_PARAMETER rtsp://x/1 RTSP/1.0\r\nCSeq: 2\r\nContent-Type: image/jpeg\r\nContent-Length: ${image.size}\r\n\r\n".toByteArray() +
                image
        )
        val first = Rtsp.readRequest(stream)!!
        assertEquals("OPTIONS", first.method)
        assertEquals("abc", first.header("Apple-Challenge")) // header names are case-insensitive
        val second = Rtsp.readRequest(stream)!!
        assertEquals("image/jpeg", second.contentType)
        assertArrayEquals(image, second.body)
        assertNull(Rtsp.readRequest(stream))
    }

    @Test
    fun `writes a response with content length`() {
        val out = ByteArrayOutputStream()
        Rtsp.writeResponse(out, RtspResponse().header("CSeq", "3").text("text/parameters", "volume: -10.000000\r\n"))
        assertEquals(
            "RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Type: text/parameters\r\nContent-Length: 20\r\n\r\nvolume: -10.000000\r\n",
            out.toString(),
        )
    }
}
