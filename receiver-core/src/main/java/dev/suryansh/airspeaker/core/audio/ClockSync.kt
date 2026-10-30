package dev.suryansh.airspeaker.core.audio

/**
 * Maps sender RTP timestamps to local [System.nanoTime] instants, following classic Shairport Sync:
 *
 * - Timing-port exchanges estimate `offset = senderClock − localClock`. The sample with the
 *   smallest round trip among the most recent ones wins, since it has the least queueing noise.
 * - Control-port sync packets give an anchor: the frame `syncRtp − latency` plays at sender time
 *   `syncTime`, so frame F plays at `syncTime + (F − anchorRtp) / rate`.
 *
 * Thread-safe: the network threads write, the player thread reads.
 */
class ClockSync(private val sampleRate: Int = 44100) {
    private class Sample(val offsetNs: Long, val rttNs: Long)

    private val samples = ArrayDeque<Sample>()
    private var timingOffsetNs: Long? = null
    private var fallbackOffsetNs: Long? = null

    private var anchorRtp = 0
    private var anchorRemoteNs = 0L
    private var anchored = false

    /** User adjustment added to every play time (positive = later), for outputs with extra delay. */
    @Volatile
    var extraDelayNs: Long = 0

    /** Latency in frames between a frame being "current" at the sender and it being played. */
    var latencyFrames: Int = DEFAULT_LATENCY
        private set

    @get:Synchronized
    val hasAnchor: Boolean get() = anchored

    @get:Synchronized
    val offsetNs: Long? get() = timingOffsetNs ?: fallbackOffsetNs

    @Synchronized
    fun addTimingSample(t1LocalNs: Long, t2RemoteNs: Long, t3RemoteNs: Long, t4LocalNs: Long) {
        val rtt = (t4LocalNs - t1LocalNs) - (t3RemoteNs - t2RemoteNs)
        if (rtt < 0 || rtt > MAX_RTT_NS) return
        val offset = ((t2RemoteNs - t1LocalNs) + (t3RemoteNs - t4LocalNs)) / 2
        samples.addLast(Sample(offset, rtt))
        while (samples.size > WINDOW) samples.removeFirst()
        timingOffsetNs = samples.minBy { it.rttNs }.offsetNs
    }

    /**
     * Applies a sync packet. [addFixedOffset] reproduces Shairport's extra 11025 frames for
     * modern iOS and flagged sync packets.
     */
    @Synchronized
    fun applySync(syncRtp: Int, rtpLessLatency: Int, remoteNs: Long, addFixedOffset: Boolean, arrivalLocalNs: Long) {
        var latency = syncRtp - rtpLessLatency
        if (addFixedOffset) latency += FIXED_LATENCY_OFFSET
        if (latency in MIN_LATENCY..MAX_LATENCY) latencyFrames = latency
        anchorRtp = syncRtp - latencyFrames
        anchorRemoteNs = remoteNs
        anchored = true
        // Without timing replies, assume the sync packet took no time to arrive.
        if (fallbackOffsetNs == null) fallbackOffsetNs = remoteNs - arrivalLocalNs
    }

    /**
     * Fallback when a sender never sends sync packets: frame [rtp] plays [latencyFrames] after
     * [localNs], the moment it arrived.
     */
    @Synchronized
    fun anchorAtLocalTime(rtp: Int, localNs: Long) {
        val offset = timingOffsetNs ?: fallbackOffsetNs ?: 0L.also { fallbackOffsetNs = it }
        anchorRtp = rtp
        anchorRemoteNs = localNs + offset + latencyFrames * 1_000_000_000L / sampleRate
        anchored = true
    }

    /** Forgets the anchor (after FLUSH the sender re-anchors before resuming). */
    @Synchronized
    fun clearAnchor() {
        anchored = false
    }

    /** Local monotonic time at which the frame with RTP timestamp [rtp] should be heard, or null. */
    @Synchronized
    fun localTimeOf(rtp: Int): Long? {
        if (!anchored) return null
        val offset = timingOffsetNs ?: fallbackOffsetNs ?: return null
        val frames = (rtp - anchorRtp).toLong() // 32-bit wrap-safe difference
        return anchorRemoteNs + frames * 1_000_000_000L / sampleRate - offset + extraDelayNs
    }

    companion object {
        const val DEFAULT_LATENCY = 88200 // 2 s, what classic senders use
        const val FIXED_LATENCY_OFFSET = 11025
        private const val MIN_LATENCY = 11025
        private const val MAX_LATENCY = 4 * 44100
        private const val WINDOW = 8
        private const val MAX_RTT_NS = 500_000_000L

        /** NTP 64-bit (seconds since 1900 . fraction) to nanoseconds. */
        fun ntpToNanos(seconds: Long, fraction: Long): Long = seconds * 1_000_000_000L + ((fraction * 1_000_000_000L) ushr 32)

        fun nanosToNtp(nanos: Long): Pair<Long, Long> {
            val seconds = nanos / 1_000_000_000L
            val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
            return seconds to fraction
        }
    }
}
