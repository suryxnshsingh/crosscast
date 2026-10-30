package dev.suryansh.airspeaker.core.meta

/**
 * DMAP ("Digital Media Access Protocol") as sent in `application/x-dmap-tagged` SET_PARAMETER
 * bodies: a sequence of `tag(4) length(4, big-endian) value`, where container tags such as
 * `mlit` hold further items.
 */
object Dmap {
    private val CONTAINERS = setOf("mlit", "mlcl", "mcon", "msrv", "cmst", "mdcl")
    private val STRINGS = setOf("minm", "asar", "asal", "asaa", "asgn", "ascm", "ascp", "assn", "assa", "assl")

    /** Flattens the body into tag → value (String for text tags, Long for numbers, ByteArray otherwise). */
    fun parse(body: ByteArray): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        walk(body, 0, body.size, out, depth = 0)
        return out
    }

    private fun walk(b: ByteArray, from: Int, to: Int, out: MutableMap<String, Any>, depth: Int) {
        var i = from
        while (i + 8 <= to) {
            val tag = String(b, i, 4, Charsets.ISO_8859_1)
            val length = ((b[i + 4].toInt() and 0xFF) shl 24) or ((b[i + 5].toInt() and 0xFF) shl 16) or
                ((b[i + 6].toInt() and 0xFF) shl 8) or (b[i + 7].toInt() and 0xFF)
            val start = i + 8
            if (length < 0 || start + length > to) return // truncated or garbage
            when {
                tag in CONTAINERS && depth < 4 -> walk(b, start, start + length, out, depth + 1)
                tag in STRINGS -> out[tag] = String(b, start, length, Charsets.UTF_8)
                length in 1..8 -> out[tag] = number(b, start, length)
                else -> out[tag] = b.copyOfRange(start, start + length)
            }
            i = start + length
        }
    }

    private fun number(b: ByteArray, start: Int, length: Int): Long {
        var v = 0L
        for (k in 0 until length) v = (v shl 8) or (b[start + k].toLong() and 0xFF)
        return v
    }
}

data class TrackInfo(val title: String?, val artist: String?, val album: String?, val durationMs: Long?) {
    companion object {
        fun from(dmap: Map<String, Any>) = TrackInfo(
            title = (dmap["minm"] as? String)?.takeIf { it.isNotBlank() },
            artist = (dmap["asar"] as? String)?.takeIf { it.isNotBlank() },
            album = (dmap["asal"] as? String)?.takeIf { it.isNotBlank() },
            durationMs = (dmap["astm"] as? Long)?.takeIf { it > 0 },
        )
    }
}

/** `progress: start/current/end`, all RTP timestamps (44.1 kHz, unsigned 32-bit). */
data class Progress(val positionMs: Long, val durationMs: Long) {
    companion object {
        fun parse(value: String, sampleRate: Int = 44100): Progress? {
            val parts = value.trim().split('/')
            if (parts.size != 3) return null
            val (start, current, end) = parts.map { it.trim().toLongOrNull() ?: return null }
            val position = (current - start) and 0xFFFFFFFFL // unsigned difference survives RTP wrap
            val duration = (end - start) and 0xFFFFFFFFL
            return Progress(position * 1000 / sampleRate, duration * 1000 / sampleRate)
        }
    }
}
