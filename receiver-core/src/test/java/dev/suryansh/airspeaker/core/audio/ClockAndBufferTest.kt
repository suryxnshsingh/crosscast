package dev.suryansh.airspeaker.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ClockAndBufferTest {
    @Test
    fun `NTP conversion round-trips to the nanosecond scale`() {
        val ns = 3_900_000_000L * 1_000_000_000L + 123_456_789L // seconds since 1900, in 2023
        val (s, f) = ClockSync.nanosToNtp(ns)
        assertTrue(abs(ClockSync.ntpToNanos(s, f) - ns) <= 1)
    }

    @Test
    fun `offset comes from the lowest round-trip sample`() {
        val clock = ClockSync()
        val trueOffset = 5_000_000_000L
        // A noisy sample (asymmetric 40 ms delay) then a clean one (1 ms each way).
        clock.addTimingSample(t1LocalNs = 0, t2RemoteNs = 40_000_000 + trueOffset, t3RemoteNs = 40_000_000 + trueOffset, t4LocalNs = 41_000_000)
        clock.addTimingSample(t1LocalNs = 100_000_000, t2RemoteNs = 101_000_000 + trueOffset, t3RemoteNs = 101_000_000 + trueOffset, t4LocalNs = 102_000_000)
        assertEquals(trueOffset, clock.offsetNs)
    }

    @Test
    fun `frame play time follows the sync anchor plus latency`() {
        val clock = ClockSync()
        val offset = 1_000_000_000L
        clock.addTimingSample(0, offset, offset, 0)
        assertNull(clock.localTimeOf(0))

        // iOS: syncRtp − rtpLessLatency = 77175, plus Shairport's 11025 = 88200 frames (2 s).
        val remoteSync = 10_000_000_000L
        clock.applySync(syncRtp = 100_000, rtpLessLatency = 100_000 - 77_175, remoteNs = remoteSync, addFixedOffset = true, arrivalLocalNs = 0)
        assertEquals(88_200, clock.latencyFrames)
        // The frame that is "now" at the sender plays 2 s later, in local time.
        assertEquals(remoteSync + 2_000_000_000L - offset, clock.localTimeOf(100_000))
        // One second of frames later plays one second later.
        assertEquals(remoteSync + 3_000_000_000L - offset, clock.localTimeOf(100_000 + 44_100))
    }

    @Test
    fun `RTP timestamps wrap at 32 bits`() {
        val clock = ClockSync()
        clock.addTimingSample(0, 0, 0, 0)
        clock.applySync(syncRtp = Int.MAX_VALUE, rtpLessLatency = Int.MAX_VALUE - 88_200, remoteNs = 0, addFixedOffset = false, arrivalLocalNs = 0)
        val before = clock.localTimeOf(Int.MAX_VALUE)!!
        val after = clock.localTimeOf(Int.MAX_VALUE + 44_100)!! // wraps negative
        assertEquals(1_000_000_000L, after - before)
    }

    @Test
    fun `jitter buffer keys by sequence and tracks the earliest packet`() {
        val buffer = JitterBuffer(framesPerPacket = 4, capacity = 8)
        val pcm = ShortArray(8) { it.toShort() }
        buffer.put(seq = 65535, rtp = 1000, pcm = pcm, frames = 4)
        buffer.put(seq = 1, rtp = 1008, pcm = pcm, frames = 4)
        buffer.put(seq = 65534, rtp = 996, pcm = pcm, frames = 4) // late arrival before the head
        assertEquals(65534, buffer.awaitHead(0)!!.seq)

        val out = ShortArray(8)
        assertEquals(4, buffer.take(65535, out))
        assertEquals(7.toShort(), out[7])
        assertEquals(-1, buffer.take(65535, out)) // taken
        assertEquals(-1, buffer.take(0, out)) // never arrived

        assertTrue(JitterBuffer.seqBefore(65535, 0))
        assertFalse(JitterBuffer.seqBefore(0, 65535))
        buffer.clear()
        assertNull(buffer.awaitHead(0))
    }

    @Test
    fun `volume maps the AirPlay slider onto 45 dB of gain`() {
        assertEquals(1f, Volume.gain(0.0), 1e-6f)
        assertEquals(0f, Volume.gain(Volume.MUTE), 0f)
        assertEquals(0.0056f, Volume.gain(-30.0), 0.0001f) // -45 dB
        assertEquals(0.0750f, Volume.gain(-15.0), 0.0001f) // -22.5 dB
    }
}
