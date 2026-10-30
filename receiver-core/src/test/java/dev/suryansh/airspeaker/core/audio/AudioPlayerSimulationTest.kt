package dev.suryansh.airspeaker.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Drives [AudioPlayer] against a simulated sender, network and DAC on a virtual clock, then
 * checks when each packet was *really* heard against when the sender wanted it heard.
 */
class AudioPlayerSimulationTest {

    private class SimTime {
        var now = 0L
    }

    /** A DAC with a fixed-size FIFO, consuming at [framesPerSecond] of local time, heard [latencyNs] later. */
    private class SimSink(
        private val t: SimTime,
        private val framesPerSecond: Double,
        private val latencyNs: Long,
        private val jitterNs: Long,
        private val random: Random,
        private val capacity: Int = 8820,
    ) : PcmSink {
        private var startNs = -1L
        var written = 0L
            private set
        var underruns = 0
            private set

        private fun consumed(now: Long): Long =
            if (startNs < 0) 0 else minOf(written, ((now - startNs) * framesPerSecond / 1e9).toLong())

        override fun write(data: ShortArray, frames: Int): Int {
            if (startNs < 0) startNs = t.now
            else if (consumed(t.now) >= written) underruns++
            val queued = written - consumed(t.now)
            val overflow = queued + frames - capacity
            if (overflow > 0) t.now += ceil(overflow * 1e9 / framesPerSecond).toLong() // block
            written += frames
            return frames
        }

        fun heardAtNs(frameIndex: Long): Long = startNs + (frameIndex * 1e9 / framesPerSecond).toLong() + latencyNs

        override fun nextPresentationTimeNs(): Long? {
            if (startNs < 0 || t.now - startNs < 30_000_000) return null // timestamps take a moment to appear
            val jitter = if (jitterNs > 0) random.nextLong(-jitterNs, jitterNs) else 0
            return heardAtNs(written) + jitter
        }

        override fun setGain(gain: Float) = Unit
        override fun close() = Unit
    }

    private class Result(val maxErrorMs: Double, val meanAbsErrorMs: Double, val player: AudioPlayer, val sink: SimSink)

    private fun simulate(
        minutes: Int,
        senderPpm: Double,
        dacPpm: Double,
        lossRate: Double = 0.001,
        timestampJitterNs: Long = 1_500_000,
        seed: Int = 1,
    ): Result {
        val random = Random(seed)
        val t = SimTime()
        val rate = 44100
        val fpp = 352
        val latencyFrames = 88200
        val senderOffsetNs = 5_000_000_000L
        fun senderTime(local: Long) = (local * (1 + senderPpm * 1e-6)).toLong() + senderOffsetNs
        fun localTime(sender: Long) = ((sender - senderOffsetNs) / (1 + senderPpm * 1e-6)).toLong()

        val rtp0 = 1_000_000
        val s0 = senderTime(0)
        fun sendTime(frame: Long) = s0 + (frame - rtp0) * 1_000_000_000L / rate
        // The sender wants each frame heard exactly `latency` after it sends it.
        fun trueDueLocal(rtp: Int) = localTime(sendTime(rtp.toLong()) + latencyFrames * 1_000_000_000L / rate)

        val clock = ClockSync(rate)
        val buffer = JitterBuffer(fpp, time = { t.now })
        val sink = SimSink(t, rate * (1 + dacPpm * 1e-6), latencyNs = 50_000_000, jitterNs = timestampJitterNs, random = random)
        val player = AudioPlayer(
            buffer, clock, fpp, rate,
            listener = object : AudioPlayer.Listener {
                override fun onOutputStarted() = Unit
                override fun onOutputStopped() = Unit
            },
            openSink = { sink },
            time = { t.now },
            pause = { ms -> t.now += ms * 1_000_000 },
        )

        val warmUpNs = 5_000_000_000L
        var maxErr = 0.0
        var sumErr = 0.0
        var count = 0
        player.onPacketWritten = { rtp, _ ->
            val err = (sink.heardAtNs(sink.written) - trueDueLocal(rtp)) / 1e6
            if (t.now > warmUpNs) {
                maxErr = maxOf(maxErr, abs(err))
                sumErr += abs(err)
                count++
            }
        }

        val pcm = ShortArray(fpp * 2)
        val endNs = minutes * 60_000_000_000L
        t.now = 10_000_000 // the first packet has arrived
        var steps = 0L
        var nextPacket = 0L
        var nextSyncNs = 0L
        var nextTimingNs = 0L
        while (t.now < endNs) {
            // Packets the sender has sent and the network has delivered by now.
            while (true) {
                val frame = rtp0 + nextPacket * fpp
                val arrival = localTime(sendTime(frame)) + 1_000_000 + random.nextLong(7_000_000)
                if (arrival > t.now) break
                if (random.nextDouble() >= lossRate) buffer.put((nextPacket and 0xFFFF).toInt(), frame.toInt(), pcm, fpp)
                nextPacket++
            }
            // A sync packet every second, naming the frame being sent "now".
            if (t.now >= nextSyncNs) {
                val senderNow = senderTime(t.now)
                val syncRtp = (rtp0 + (senderNow - s0) * rate / 1_000_000_000L).toInt()
                clock.applySync(syncRtp, syncRtp - 77_175, senderNow, addFixedOffset = true, arrivalLocalNs = t.now)
                nextSyncNs += 1_000_000_000L
            }
            // A timing exchange every 3 s with 1–4 ms each way.
            if (t.now >= nextTimingNs) {
                val t1 = t.now
                val t2 = senderTime(t1 + 1_000_000 + random.nextLong(3_000_000))
                val t3 = t2 + 50_000
                val t4 = localTime(t3) + 1_000_000 + random.nextLong(3_000_000)
                clock.addTimingSample(t1, t2, t3, t4)
                nextTimingNs += 3_000_000_000L
            }
            val before = t.now
            player.step()
            // Steps that don't block (e.g. filling an empty sink) still take a little time.
            if (t.now == before) t.now += 20_000
            check(++steps < 50_000_000) { "simulation stuck at ${t.now / 1e9} s" }
        }
        println("ppm %+.0f/%+.0f loss %.3f: max %.2f ms, mean %.2f ms, +%d/-%d frames, %d missing, %d realigned".format(
            senderPpm, dacPpm, lossRate, maxErr, sumErr / count, player.framesInserted, player.framesDropped, player.packetsMissing, player.realignments))
        return Result(maxErr, sumErr / count, player, sink)
    }

    @Test
    fun `keeps the sync error small over 30 minutes with 100 ppm of drift`() {
        val r = simulate(minutes = 30, senderPpm = 60.0, dacPpm = -40.0)
        assertTrue("max error ${r.maxErrorMs} ms", r.maxErrorMs < 3.5)
        assertTrue("mean error ${r.meanAbsErrorMs} ms", r.meanAbsErrorMs < 2.0)
        assertEquals(0, r.player.realignments)
        assertEquals(0, r.sink.underruns)
        // 100 ppm of a 44.1 kHz stream for 30 minutes is about 7,900 frames to absorb.
        val corrected = r.player.framesDropped - r.player.framesInserted
        assertTrue("net correction $corrected frames", corrected in 7_000..9_000)
    }

    @Test
    fun `drift in the other direction is corrected by inserting frames`() {
        val r = simulate(minutes = 10, senderPpm = -50.0, dacPpm = 50.0, seed = 2)
        assertTrue("max error ${r.maxErrorMs} ms", r.maxErrorMs < 3.5)
        assertTrue("mean error ${r.meanAbsErrorMs} ms", r.meanAbsErrorMs < 2.0)
        assertTrue(r.player.framesInserted > r.player.framesDropped)
        assertEquals(0, r.player.realignments)
    }

    @Test
    fun `heavy packet loss does not break sync`() {
        val r = simulate(minutes = 5, senderPpm = 20.0, dacPpm = 0.0, lossRate = 0.05, seed = 3)
        assertTrue("max error ${r.maxErrorMs} ms", r.maxErrorMs < 3.5)
        assertTrue("mean error ${r.meanAbsErrorMs} ms", r.meanAbsErrorMs < 2.0)
        assertEquals(0, r.player.realignments)
    }
}
