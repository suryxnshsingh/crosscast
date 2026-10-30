package dev.suryansh.airspeaker.core.raop

import dev.suryansh.airspeaker.core.audio.AudioPlayer
import dev.suryansh.airspeaker.core.audio.ClockSync
import dev.suryansh.airspeaker.core.audio.JitterBuffer
import java.io.Closeable
import java.net.InetAddress

/** One stream from SETUP to TEARDOWN: UDP receivers, jitter buffer, clock and player. */
class PlaybackSession(
    announce: Announce,
    sender: InetAddress,
    senderControlPort: Int,
    senderTimingPort: Int,
    addFixedLatency: (flags: Int) -> Boolean,
    listener: AudioPlayer.Listener,
    decodedTap: ((pcm: ShortArray, frames: Int) -> Unit)? = null,
) : Closeable {
    private val clock = ClockSync(announce.alac.sampleRate)
    private val buffer = JitterBuffer(announce.framesPerPacket)
    val rtp = RtpSession(sender, senderControlPort, senderTimingPort, announce, buffer, clock, addFixedLatency, decodedTap)
    private val player = AudioPlayer(buffer, clock, announce.framesPerPacket, announce.alac.sampleRate, listener)
    private var playerStarted = false

    fun startReceiving() = rtp.start()

    @Synchronized
    fun startPlaying() {
        if (playerStarted) return
        playerStarted = true
        player.start()
    }

    fun flush(rtpTime: Int?) {
        rtp.flush(rtpTime)
        buffer.clear()
        clock.clearAnchor()
        player.flush()
    }

    fun setGain(gain: Float) = player.setGain(gain)

    fun setLatencyOffsetMs(ms: Int) {
        clock.extraDelayNs = ms * 1_000_000L
    }

    val lastPacketAtNs: Long get() = rtp.lastPacketAtNs

    override fun close() {
        rtp.close()
        player.close()
    }
}
