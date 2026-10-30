package dev.suryansh.airspeaker.core.mdns

import dev.suryansh.airspeaker.core.ReceiverIdentity

/** What we advertise as `_raop._tcp`: classic AirPlay audio. */
object RaopService {
    const val TYPE = "_raop._tcp"

    /** The port the RTSP server listens on; classic Shairport Sync uses the same default. */
    const val RTSP_PORT = 5000

    private const val MAX_LABEL_BYTES = 63

    /**
     * TXT records, following classic Shairport Sync 5.x (mdns.h / bonjour_strings.c), which is
     * tested against current iOS. We only implement UDP transport, so `tp=UDP`.
     */
    val txtRecords: Map<String, String> = linkedMapOf(
        "txtvers" to "1",
        "ch" to "2", // stereo
        "cn" to "0,1", // codecs: PCM, ALAC
        "et" to "0,1", // encryption: none, RSA
        "ek" to "1",
        "sr" to "44100",
        "ss" to "16",
        "tp" to "UDP",
        "vn" to "65537",
        "vs" to "105.1", // server version; senders gate progress and artwork on this
        "md" to "0,1,2", // metadata: text, artwork, progress
        "am" to "AirSpeaker1,1",
        "da" to "true",
        "sv" to "false",
        "sf" to "0x4",
        "fv" to "76400.10",
        "pw" to "false",
    )

    /** `0A1B2C3D4E5F@Room Speaker`, truncated to fit one DNS label without splitting a character. */
    fun serviceName(identity: ReceiverIdentity): String {
        val prefix = "${identity.deviceId}@"
        val budget = MAX_LABEL_BYTES - prefix.length
        val name = StringBuilder()
        var bytes = 0
        for (cp in identity.friendlyName.trim().codePoints()) {
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > budget) break
            name.appendCodePoint(cp)
            bytes += size
        }
        return prefix + name.toString().trimEnd()
    }
}
