package dev.suryansh.crosscast.core.meta

/**
 * Where we are in the current track, for the progress bar.
 *
 * The sender's `progress` names the RTP time at which the track starts, and the player reports
 * which RTP frame is heard when it starts (or resumes) audible playback. Together they give
 * the position of the audio actually coming out of the speaker. It advances only while audio is
 * heard and freezes the moment it isn't (pause, track skip, network gap).
 *
 * All times are milliseconds on one monotonic clock. Not thread-safe; the engine serialises calls.
 */
class TrackClock(private val sampleRate: Int = 44100) {
    /** [positionMs] at [atMs]; it moves on in real time from there while [advancing]. */
    data class Position(val positionMs: Long?, val atMs: Long, val advancing: Boolean, val durationMs: Long?)

    private class Heard(val rtp: Int, val atMs: Long)

    private var startRtp: Int? = null
    private var durationMs: Long? = null
    private var heard: Heard? = null
    private var frozenMs: Long? = null

    fun onProgress(progress: Progress, nowMs: Long): Position {
        startRtp = progress.startRtp
        durationMs = progress.durationMs
        val h = heard
        if (h != null) return Position(msFromStart(h.rtp), h.atMs, advancing = true, durationMs)
        frozenMs = progress.positionMs
        return Position(frozenMs, nowMs, advancing = false, durationMs)
    }

    /** Frame [rtp] is heard at [atMs]: audible playback has started or resumed. */
    fun onHeard(rtp: Int, atMs: Long): Position {
        val wasFrozenMs = positionAt(atMs)
        heard = Heard(rtp, atMs)
        // A sender without progress: carry on from where we stopped.
        val position = if (startRtp != null) msFromStart(rtp) else wasFrozenMs
        return Position(position, atMs, advancing = true, durationMs)
    }

    /** Audio stopped (pause, flush, gap): freeze at what was last heard. */
    fun onStopped(nowMs: Long): Position {
        frozenMs = positionAt(nowMs)
        heard = null
        return Position(frozenMs, nowMs, advancing = false, durationMs)
    }

    fun reset() {
        startRtp = null
        durationMs = null
        heard = null
        frozenMs = null
    }

    private fun positionAt(nowMs: Long): Long? {
        val h = heard ?: return frozenMs
        val start = startRtp
        val base = if (start != null) msFromStart(h.rtp) else frozenMs ?: return null
        val position = base + (nowMs - h.atMs)
        return durationMs?.let { position.coerceIn(0, it) } ?: position.coerceAtLeast(0)
    }

    /** Milliseconds from the track start to frame [rtp]; negative before the track starts. */
    private fun msFromStart(rtp: Int): Long = (rtp - startRtp!!).toLong() * 1000 / sampleRate
}
