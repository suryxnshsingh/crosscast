package dev.suryansh.airspeaker.core

import android.graphics.Bitmap

/** Everything the UI needs, from one place. Updated by the engine as RTSP messages arrive. */
data class NowPlayingState(
    val status: Status = Status.Idle,
    val senderName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    /** Track position at [positionAtElapsedMs] (SystemClock.elapsedRealtime), for extrapolating while playing. */
    val positionMs: Long? = null,
    val positionAtElapsedMs: Long = 0,
    val durationMs: Long? = null,
    /** AirPlay volume, −30..0 dB or −144 for mute. */
    val volume: Double? = null,
) {
    enum class Status { Idle, Playing, Paused }
}
