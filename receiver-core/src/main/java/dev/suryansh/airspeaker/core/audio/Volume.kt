package dev.suryansh.airspeaker.core.audio

import kotlin.math.pow

/**
 * AirPlay volume is −30.0 (quietest) to 0.0 dB, or −144 for mute, linear in slider position.
 * The slider is applied over [RANGE_DB] of attenuation as track gain; the TV's own volume stays
 * the master. 30 dB applies the sender's dB as sent: half way is −15 dB.
 * (45 dB made the middle of the slider too quiet.) The system volume is never touched.
 */
object Volume {
    const val MUTE = -144.0
    const val MIN = -30.0
    const val RANGE_DB = 30.0

    fun gain(airplayVolume: Double): Float {
        if (airplayVolume.isNaN() || airplayVolume <= MIN - 0.0001) return 0f
        val position = (airplayVolume.coerceIn(MIN, 0.0) - MIN) / -MIN // 0..1
        val db = (position - 1.0) * RANGE_DB
        return 10.0.pow(db / 20.0).toFloat()
    }
}
