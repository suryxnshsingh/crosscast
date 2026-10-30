package dev.suryansh.airspeaker.core.audio

import kotlin.math.pow

/**
 * AirPlay volume is −30.0 (quietest) to 0.0 dB, or −144 for mute, linear in slider position.
 * We spread that slider over [RANGE_DB] of attenuation so the bottom of the slider is really
 * quiet, then apply it as track gain. The system volume is never touched.
 */
object Volume {
    const val MUTE = -144.0
    const val RANGE_DB = 45.0

    fun gain(airplayVolume: Double): Float {
        if (airplayVolume.isNaN() || airplayVolume <= -30.0001) return 0f
        val position = (airplayVolume.coerceIn(-30.0, 0.0) + 30.0) / 30.0 // 0..1
        val db = (position - 1.0) * RANGE_DB
        return 10.0.pow(db / 20.0).toFloat()
    }
}
