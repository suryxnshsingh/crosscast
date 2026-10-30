package dev.suryansh.crosscast.core.audio

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

/**
 * iOS opens every classic AirPlay session at −20, whatever the last level was, and a receiver
 * can't move the iPhone's slider (DACP `setproperty?dmcp.device-volume` is accepted and ignored).
 * So the receiver pins the loudness it last played at ([anchor], in the same −30..0 dB scale) to
 * the slider position the session [opened] at. From there the slider still reaches −30 at the
 * bottom and 0 dB at the top, spread linearly on each side. When the two agree (nothing
 * remembered, or a session that iOS resumed at its own level) this is the identity.
 */
class VolumeCurve(private val opened: Double, private val anchor: Double) {
    /** The level to play at, −30..0 dB, or [Volume.MUTE] for mute, for the sender's [volume]. */
    fun effective(volume: Double): Double {
        if (volume.isNaN() || volume < Volume.MIN) return Volume.MUTE
        if (opened < Volume.MIN || opened >= 0.0) return volume // nothing sensible to pin to
        val a = anchor.coerceIn(Volume.MIN, 0.0)
        val v = volume.coerceAtMost(0.0)
        return if (v >= opened) {
            a + (v - opened) / -opened * -a
        } else {
            Volume.MIN + (v - Volume.MIN) / (opened - Volume.MIN) * (a - Volume.MIN)
        }
    }
}
