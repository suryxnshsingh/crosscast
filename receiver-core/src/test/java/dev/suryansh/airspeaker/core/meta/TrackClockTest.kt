package dev.suryansh.airspeaker.core.meta

import dev.suryansh.airspeaker.core.NowPlayingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackClockTest {
    private val rate = 44100
    private val start = 1_000_000

    /** `progress` for a 4-minute track with the sender's play point [atMs] in. */
    private fun progress(atMs: Long, startRtp: Int = start) =
        Progress.parse("${startRtp.toUInt()}/${(startRtp + (atMs * rate / 1000).toInt()).toUInt()}/${(startRtp + 240 * rate).toUInt()}")!!

    private fun rtpAt(ms: Long) = start + (ms * rate / 1000).toInt()

    private fun TrackClock.Position.shownAt(nowMs: Long) = NowPlayingState(
        status = if (advancing) NowPlayingState.Status.Playing else NowPlayingState.Status.Paused,
        positionMs = positionMs,
        positionAtElapsedMs = atMs,
        durationMs = durationMs,
    ).positionMsAt(nowMs)

    @Test
    fun `follows the frame being heard, not the sender's play point`() {
        val clock = TrackClock(rate)
        // Progress arrives as the sender starts sending; its first frame is heard 2 s later.
        val p = clock.onProgress(progress(atMs = 0), nowMs = 10_000)
        assertFalse(p.advancing)
        assertEquals(0L, p.shownAt(11_000))
        val heard = clock.onHeard(rtpAt(0), atMs = 12_000)
        assertTrue(heard.advancing)
        assertEquals(0L, heard.shownAt(12_000))
        assertEquals(30_000L, heard.shownAt(42_000))
    }

    @Test
    fun `pausing freezes the position at what was heard, and resuming carries on`() {
        val clock = TrackClock(rate)
        clock.onProgress(progress(atMs = 0), nowMs = 0)
        clock.onHeard(rtpAt(0), atMs = 2_000)
        val paused = clock.onStopped(nowMs = 62_000) // FLUSH after 60 s of audio
        assertEquals(60_000L, paused.positionMs)
        assertEquals(60_000L, paused.shownAt(100_000)) // stays put while paused
        // iOS resumes from the next RTP frame after the flush; 2 s later it's heard.
        val resumed = clock.onHeard(rtpAt(60_000), atMs = 120_000)
        assertEquals(60_000L, resumed.shownAt(120_000))
        assertEquals(65_000L, resumed.shownAt(125_000))
    }

    @Test
    fun `next track in a queue shows 0 until its first frame is heard`() {
        val clock = TrackClock(rate)
        clock.onProgress(progress(atMs = 0), nowMs = 0)
        clock.onHeard(rtpAt(0), atMs = 2_000)
        // 240 s in, the sender moves on to a track starting at the RTP it is sending now,
        // while the last 2 s of the previous one are still to be heard.
        val nextStart = rtpAt(240_000)
        val p = clock.onProgress(progress(atMs = 0, startRtp = nextStart), nowMs = 240_000)
        assertTrue(p.advancing)
        assertEquals(0L, p.shownAt(241_000))
        assertEquals(1_000L, p.shownAt(243_000))
    }

    @Test
    fun `without progress, the position resumes from where it froze`() {
        val clock = TrackClock(rate)
        clock.onHeard(rtpAt(0), atMs = 0)
        assertEquals(null, clock.onStopped(nowMs = 5_000).positionMs)
        clock.onProgress(progress(atMs = 10_000), nowMs = 6_000)
        clock.onStopped(nowMs = 7_000)
        assertEquals(10_000L, clock.onStopped(nowMs = 8_000).positionMs)
    }
}
