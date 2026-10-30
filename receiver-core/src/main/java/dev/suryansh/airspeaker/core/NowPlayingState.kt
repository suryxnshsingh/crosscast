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
    /**
     * Track position at [positionAtElapsedMs] (SystemClock.elapsedRealtime), moving on in real time
     * while [Status.Playing]. Negative until the track's first frame is heard.
     */
    val positionMs: Long? = null,
    val positionAtElapsedMs: Long = 0,
    val durationMs: Long? = null,
    /** AirPlay volume, −30..0 dB or −144 for mute. */
    val volume: Double? = null,
) {
    /**
     * - Playing: audio is being heard.
     * - Buffering: audio is arriving but not yet due (a new stream, or a new track after a skip).
     * - Paused: a sender is connected but sends nothing (paused, or between a FLUSH and new audio).
     */
    enum class Status { Idle, Buffering, Playing, Paused }

    /** The position to show at [elapsedMs]: frozen unless playing, and within the track. */
    fun positionMsAt(elapsedMs: Long): Long? {
        val base = positionMs ?: return null
        val position = if (status == Status.Playing) base + (elapsedMs - positionAtElapsedMs) else base
        return durationMs?.let { position.coerceIn(0, it) } ?: position.coerceAtLeast(0)
    }
}
