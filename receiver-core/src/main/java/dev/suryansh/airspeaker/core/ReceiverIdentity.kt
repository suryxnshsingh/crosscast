package dev.suryansh.airspeaker.core

import java.security.SecureRandom
import java.util.Random

/**
 * How the receiver presents itself to senders.
 *
 * [deviceId] is 12 upper-case hex digits shaped like a MAC address. It prefixes the Bonjour name
 * and is the hardware address in the Apple-Challenge response, so it must never change.
 */
data class ReceiverIdentity(val deviceId: String, val friendlyName: String) {
    init {
        require(DeviceIds.isValid(deviceId)) { "invalid device id: $deviceId" }
    }

    /** The 6 bytes of [deviceId]. */
    val hardwareAddress: ByteArray
        get() = ByteArray(6) { deviceId.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

object DeviceIds {
    /** A random locally-administered unicast MAC, so it can never clash with real hardware. */
    fun generate(random: Random = SecureRandom()): String {
        val bytes = ByteArray(6).also(random::nextBytes)
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun isValid(id: String): Boolean = id.length == 12 && id.all { it in '0'..'9' || it in 'A'..'F' }
}
