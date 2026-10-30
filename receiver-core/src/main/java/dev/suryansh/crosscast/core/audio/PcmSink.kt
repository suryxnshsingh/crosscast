package dev.suryansh.crosscast.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack

/** Where decoded audio goes. An interface so the sync logic can be tested against a simulated DAC. */
interface PcmSink {
    /** Blocking write of interleaved stereo frames. Returns frames accepted (0 if the sink is stalled) or < 0 on error. */
    fun write(data: ShortArray, frames: Int): Int

    /** Local monotonic time at which the next frame written will be heard, or null while unknown. */
    fun nextPresentationTimeNs(): Long?

    fun setGain(gain: Float)
    fun close()
}

/** Stereo 16-bit AudioTrack output, sized for stability rather than low latency. */
class AudioTrackSink(private val sampleRate: Int, gain: Float) : PcmSink {
    private val track: AudioTrack
    private val timestamp = AudioTimestamp()
    private var written = 0L

    val bufferFrames: Int get() = track.bufferSizeInFrames

    init {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 4 * BUFFER_MS / 1000))
            .build()
        track.setVolume(gain)
        track.play()
    }

    override fun write(data: ShortArray, frames: Int): Int {
        val shorts = track.write(data, 0, frames * 2)
        if (shorts < 0) return shorts
        written += shorts / 2
        return shorts / 2
    }

    override fun nextPresentationTimeNs(): Long? {
        if (!track.getTimestamp(timestamp) || timestamp.framePosition <= 0) return null
        return timestamp.nanoTime + (written - timestamp.framePosition) * 1_000_000_000L / sampleRate
    }

    override fun setGain(gain: Float) {
        track.setVolume(gain)
    }

    override fun close() {
        try {
            track.pause()
            track.flush()
        } finally {
            track.release()
        }
    }

    private companion object {
        const val BUFFER_MS = 200
    }
}
