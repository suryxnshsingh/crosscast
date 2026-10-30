package dev.suryansh.airspeaker.core.raop

import dev.suryansh.airspeaker.core.audio.AlacConfig
import java.util.Base64

enum class AudioCodec { ALAC, PCM }

/** The parts of an ANNOUNCE SDP body that the receiver needs. */
class Announce(
    val codec: AudioCodec,
    val alac: AlacConfig,
    val aesKey: ByteArray?,
    val aesIv: ByteArray?,
    val minLatency: Int?,
    val maxLatency: Int?,
    /** SDP `i=` (session information): the sender's device name, e.g. "Suryansh’s iPhone". */
    val senderName: String? = null,
) {
    val encrypted: Boolean get() = aesKey != null && aesIv != null
    val framesPerPacket: Int get() = alac.frameLength

    companion object {
        /** Parses the SDP. Throws [IllegalArgumentException] for streams we can't play (e.g. FairPlay). */
        fun parse(sdp: String, unwrapKey: (ByteArray) -> ByteArray = RaopCrypto::decryptAesKey): Announce {
            val attrs = sdp.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("a=") }
                .associate { line -> line.substring(2).substringBefore(':') to line.substringAfter(':', "") }

            require("fpaeskey" !in attrs) { "FairPlay-encrypted stream is not supported" }
            val rtpmap = requireNotNull(attrs["rtpmap"]) { "no rtpmap in SDP" }
            val codec = when {
                "AppleLossless" in rtpmap -> AudioCodec.ALAC
                "L16" in rtpmap -> AudioCodec.PCM
                else -> throw IllegalArgumentException("unsupported codec: $rtpmap")
            }
            val alac = attrs["fmtp"]?.takeIf { codec == AudioCodec.ALAC }?.let(AlacConfig::fromFmtp) ?: AlacConfig()
            val key = attrs["rsaaeskey"]?.let { unwrapKey(decodeBase64(it)) }
            val iv = attrs["aesiv"]?.let(::decodeBase64)
            return Announce(
                codec = codec,
                alac = alac,
                aesKey = key,
                aesIv = iv,
                minLatency = attrs["min-latency"]?.trim()?.toIntOrNull(),
                maxLatency = attrs["max-latency"]?.trim()?.toIntOrNull(),
                senderName = sdp.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("i=") }
                    ?.substring(2)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        /** SDP base64 often omits padding. */
        private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value.trim().trimEnd('='))
    }
}
