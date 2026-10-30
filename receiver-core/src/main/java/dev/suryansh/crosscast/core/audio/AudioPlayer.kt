package dev.suryansh.crosscast.core.audio

import android.os.Process
import dev.suryansh.crosscast.core.Logs
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Plays decoded packets from the [JitterBuffer] at the times [ClockSync] says they are due.
 *
 * - **Alignment:** at the start of each run it pads with silence (or skips packets) until the
 *   next frame written will be heard at its due time, using the sink's presentation timestamps.
 * - **Drift:** afterwards it adds or drops a single frame per packet when the smoothed error
 *   exceeds [STUFF_TOLERANCE_NS], like Shairport Sync's "basic" stuffing.
 *
 * [step] is one iteration of the playback loop; the real player runs it on its own thread, and
 * tests drive it directly against a simulated sink and clock.
 */
class AudioPlayer(
    private val buffer: JitterBuffer,
    private val clock: ClockSync,
    private val framesPerPacket: Int,
    private val sampleRate: Int,
    private val listener: Listener,
    private val openSink: (gain: Float) -> PcmSink = { gain -> AudioTrackSink(sampleRate, gain) },
    private val time: () -> Long = System::nanoTime,
    private val pause: (ms: Long) -> Unit = { Thread.sleep(it) },
) : Closeable {
    interface Listener {
        fun onOutputStarted()
        fun onOutputStopped()

        /** Frame [rtp], the first with sound after silence or a new start, is heard at [atNs] ([System.nanoTime]). */
        fun onHeard(rtp: Int, atNs: Long) = Unit

        /** What is heard has been silent since [sinceNs]: iOS pauses by streaming silence, not by stopping. */
        fun onSilent(sinceNs: Long) = Unit
    }

    @Volatile private var running = true
    @Volatile private var gain = 0f
    @Volatile private var sink: PcmSink? = null
    private val flushRequested = AtomicBoolean(false)
    private val worker = thread(start = false, name = "audio-player") { loop() }

    private val pcm = ShortArray((framesPerPacket + 1) * 2)
    private val silence = ShortArray(MAX_SILENCE_FRAMES * 2)
    private var playing = false
    private var aligned = false
    private var nextSeq = 0
    private var nextRtp = 0
    private var missingSinceNs = 0L
    private var smoothedErrNs = 0.0
    private var outOfSyncCount = 0
    private var audible = false
    private var silentSinceNs = 0L

    // Stats for the periodic log line, and for tests.
    var framesInserted = 0L
        private set
    var framesDropped = 0L
        private set
    var packetsMissing = 0L
        private set
    var realignments = 0
        private set
    private var lastStatsNs = 0L

    /** Called with the RTP timestamp and frame count of each real packet as it is handed to the sink. */
    internal var onPacketWritten: ((rtp: Int, frames: Int) -> Unit)? = null

    fun start() = worker.start()

    fun setGain(value: Float) {
        gain = value
        sink?.setGain(value)
    }

    /** Stops output now; playback restarts with the next packet after the buffer is refilled. */
    fun flush() = flushRequested.set(true)

    override fun close() {
        running = false
        worker.interrupt()
        if (worker.isAlive && Thread.currentThread() !== worker) worker.join(2_000)
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) step()
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Exception) {
            Logs.audio.e("player failed", e)
        } finally {
            stopOutput("closed")
        }
    }

    internal fun step() {
        if (flushRequested.getAndSet(false)) stopOutput("flush")
        if (!playing && !startRun()) return
        playNext()
    }

    private fun startRun(): Boolean {
        val head = buffer.awaitHead(200) ?: return false
        if (!clock.hasAnchor) {
            if (time() - head.receivedAtNs < NO_SYNC_FALLBACK_NS) {
                pause(10)
                return false
            }
            Logs.audio.w("no sync packet from the sender; anchoring on arrival time")
            clock.anchorAtLocalTime(head.rtp, head.receivedAtNs)
        }
        sink = openSink(gain)
        nextSeq = head.seq
        nextRtp = head.rtp
        aligned = false
        playing = true
        missingSinceNs = 0
        outOfSyncCount = 0
        audible = false
        silentSinceNs = 0
        Logs.audio.i("output opened")
        listener.onOutputStarted()
        return true
    }

    private fun playNext() {
        val due = clock.localTimeOf(nextRtp)
        val presented = sink?.nextPresentationTimeNs()
        if (due == null || presented == null) {
            // The sink is warming up (no timestamp yet) or the anchor was just cleared.
            write(silence, SILENCE_CHUNK_FRAMES)
            return
        }

        val errNs = presented - due // > 0: we are late
        if (!aligned) {
            if (errNs < -ALIGN_TOLERANCE_NS) {
                write(silence, nanosToFrames(-errNs).coerceIn(1, MAX_SILENCE_FRAMES))
                return
            }
            if (errNs > ALIGN_TOLERANCE_NS) {
                val skip = (nanosToFrames(errNs) / framesPerPacket).coerceAtLeast(1)
                repeat(skip) { advance(buffer.take(nextSeq, pcm).let { if (it < 0) framesPerPacket else it }) }
                Logs.audio.w("late by ${errNs / 1_000_000} ms; skipped $skip packet(s)")
                return
            }
            aligned = true
            smoothedErrNs = errNs.toDouble()
            Logs.audio.i("in sync: error ${errNs / 1000} µs, latency ${clock.latencyFrames} frames")
            audible = false // the next packet with sound reports onHeard
            silentSinceNs = 0
        } else if (abs(errNs) > RESYNC_THRESHOLD_NS) {
            // One bad timestamp is noise; a sustained error means the timeline really moved.
            if (++outOfSyncCount >= RESYNC_AFTER_PACKETS) {
                Logs.audio.w("sync error ${errNs / 1_000_000} ms; re-aligning")
                realignments++
                aligned = false
                outOfSyncCount = 0
                return
            }
        } else {
            outOfSyncCount = 0
            smoothedErrNs += (errNs - smoothedErrNs) * SMOOTHING
        }

        var frames = buffer.take(nextSeq, pcm)
        val real = frames >= 0
        if (!real) {
            frames = framesPerPacket
            pcm.fill(0, 0, frames * 2)
            packetsMissing++
            val now = time()
            if (missingSinceNs == 0L) {
                missingSinceNs = now
            } else if (now - missingSinceNs > IDLE_STOP_NS) {
                // The sender went quiet without a FLUSH; the next packet to arrive starts a new run.
                buffer.clear()
                stopOutput("no audio for ${IDLE_STOP_NS / 1_000_000_000} s")
                return
            }
        } else {
            missingSinceNs = 0
            trackAudibility(frames, due)
        }

        var out = frames
        if (smoothedErrNs > STUFF_TOLERANCE_NS && frames > 1) {
            out = frames - 1 // behind: drop the last frame
            framesDropped++
        } else if (smoothedErrNs < -STUFF_TOLERANCE_NS) {
            pcm[frames * 2] = pcm[frames * 2 - 2] // ahead: repeat the last frame
            pcm[frames * 2 + 1] = pcm[frames * 2 - 1]
            out = frames + 1
            framesInserted++
        }
        if (real) onPacketWritten?.invoke(nextRtp, out)
        write(pcm, out)
        advance(frames)
        logStats(errNs)
    }

    /** Reports when what's heard turns audible, and when it has stayed silent for [SILENCE_PAUSE_NS]. */
    private fun trackAudibility(frames: Int, dueNs: Long) {
        var silent = true
        for (i in 0 until frames * 2) {
            if (abs(pcm[i].toInt()) > SILENCE_LEVEL) {
                silent = false
                break
            }
        }
        if (!silent) {
            silentSinceNs = 0
            if (!audible) {
                audible = true
                listener.onHeard(nextRtp, dueNs)
            }
        } else {
            if (silentSinceNs == 0L) silentSinceNs = dueNs
            if (audible && dueNs - silentSinceNs >= SILENCE_PAUSE_NS) {
                audible = false
                Logs.audio.i("silent for ${SILENCE_PAUSE_NS / 1_000_000} ms")
                listener.onSilent(silentSinceNs)
            }
        }
    }

    private fun advance(frames: Int) {
        nextSeq = (nextSeq + 1) and 0xFFFF
        nextRtp += frames
    }

    private fun write(data: ShortArray, frames: Int) {
        val s = sink ?: return
        val written = s.write(data, frames)
        when {
            written < 0 -> {
                Logs.audio.w("sink write failed ($written); reopening output")
                s.close()
                sink = openSink(gain)
                aligned = false
            }
            // A stalled output (e.g. HDMI asleep) accepts nothing; don't spin.
            written == 0 -> pause(STALL_BACKOFF_MS)
        }
    }

    private fun stopOutput(reason: String) {
        sink?.let {
            sink = null
            it.close()
        }
        if (playing) {
            playing = false
            aligned = false
            Logs.audio.i("output stopped ($reason)")
            listener.onOutputStopped()
        }
    }

    private fun logStats(errNs: Long) {
        val now = time()
        if (now - lastStatsNs < STATS_INTERVAL_NS) return
        lastStatsNs = now
        Logs.audio.i(
            "sync error %.2f ms (smoothed %.2f), +%d/-%d frames, %d missing, %d realigned".format(
                errNs / 1e6, smoothedErrNs / 1e6, framesInserted, framesDropped, packetsMissing, realignments,
            )
        )
    }

    private fun nanosToFrames(ns: Long): Int = (ns * sampleRate / 1_000_000_000L).toInt()

    internal companion object {
        const val SILENCE_CHUNK_FRAMES = 441 // 10 ms
        const val MAX_SILENCE_FRAMES = 4410 // 100 ms
        const val NO_SYNC_FALLBACK_NS = 1_200_000_000L
        const val ALIGN_TOLERANCE_NS = 1_000_000L
        const val RESYNC_THRESHOLD_NS = 50_000_000L
        const val RESYNC_AFTER_PACKETS = 10
        const val STUFF_TOLERANCE_NS = 1_000_000L
        const val SMOOTHING = 0.05
        const val IDLE_STOP_NS = 3_000_000_000L
        const val STATS_INTERVAL_NS = 10_000_000_000L
        const val STALL_BACKOFF_MS = 5L

        /** −72 dBFS: below this a packet counts as silence (iOS streams digital silence while paused). */
        const val SILENCE_LEVEL = 8
        const val SILENCE_PAUSE_NS = 750_000_000L
    }
}
