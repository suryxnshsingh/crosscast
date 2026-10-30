package dev.suryansh.airspeaker.core.rtsp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap

class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name]
    val cseq: String? get() = headers["CSeq"]
    val contentType: String? get() = headers["Content-Type"]?.substringBefore(';')?.trim()
    val bodyText: String get() = String(body, Charsets.UTF_8)

    override fun toString() = "$method $uri (${body.size} bytes)"
}

class RtspResponse(var status: Int = 200, var reason: String = "OK") {
    val headers = linkedMapOf<String, String>()
    var body: ByteArray = ByteArray(0)

    fun header(name: String, value: String) = apply { headers[name] = value }

    fun text(contentType: String, text: String) = apply {
        headers["Content-Type"] = contentType
        body = text.toByteArray(Charsets.UTF_8)
    }

    fun error(status: Int, reason: String) = apply {
        this.status = status
        this.reason = reason
    }
}

class RtspProtocolException(message: String) : Exception(message)

object Rtsp {
    private const val MAX_HEADER_LINE = 8 * 1024
    private const val MAX_HEADERS = 64
    private const val MAX_BODY = 16 * 1024 * 1024 // artwork can be a few MB

    /** Reads one request, or returns null on a clean end of stream. */
    fun readRequest(input: InputStream): RtspRequest? {
        var requestLine = readLine(input) ?: return null
        while (requestLine.isEmpty()) requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("RTSP/")) throw RtspProtocolException("bad request line: $requestLine")

        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        while (true) {
            val line = readLine(input) ?: throw EOFException("end of stream in headers")
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw RtspProtocolException("too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw RtspProtocolException("bad header: $line")
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }

        val length = headers["Content-Length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw RtspProtocolException("bad Content-Length $length")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("end of stream in body")
            read += n
        }
        return RtspRequest(parts[0], parts[1], headers, body)
    }

    fun writeResponse(output: OutputStream, response: RtspResponse) {
        val head = StringBuilder()
            .append("RTSP/1.0 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        for ((name, value) in response.headers) head.append(name).append(": ").append(value).append("\r\n")
        if (response.body.isNotEmpty()) head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (response.body.isNotEmpty()) output.write(response.body)
        output.flush()
    }

    /** Reads a CRLF- or LF-terminated line as Latin-1; null on end of stream before any byte. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else throw EOFException("end of stream mid-line")
            if (b == '\n'.code) break
            if (buf.size() >= MAX_HEADER_LINE) throw RtspProtocolException("header line too long")
            buf.write(b)
        }
        val bytes = buf.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.ISO_8859_1)
    }
}
