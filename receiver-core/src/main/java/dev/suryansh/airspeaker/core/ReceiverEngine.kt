package dev.suryansh.airspeaker.core

import android.content.Context
import android.os.SystemClock
import dev.suryansh.airspeaker.core.audio.AudioFocus
import dev.suryansh.airspeaker.core.audio.Volume
import dev.suryansh.airspeaker.core.raop.RaopConnection
import dev.suryansh.airspeaker.core.raop.RaopHost
import dev.suryansh.airspeaker.core.rtsp.RtspServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * The classic AirPlay receiver: RTSP server plus whichever sender is currently playing.
 * Exposes everything the UI needs as [state].
 */
class ReceiverEngine(private val context: Context, private val identity: ReceiverIdentity) : RaopHost {
    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    /** Called with true while audio is being output; the service raises its foreground type. */
    var onStreamingChanged: (Boolean) -> Unit = {}

    /** Hook for SET_PARAMETER bodies other than volume (metadata, artwork, progress). */
    var parameterSink: (contentType: String?, body: ByteArray) -> Unit = { _, _ -> }

    private var server: RtspServer? = null
    private var active: RaopConnection? = null
    private var focusGain = 1f
    private val audioFocus = AudioFocus(context) { gain ->
        focusGain = gain
        applyGain()
    }

    val port: Int get() = server?.port ?: -1

    /** Starts the RTSP server and returns the port it listens on. */
    fun start(preferredPort: Int): Int {
        val s = RtspServer(preferredPort) { socket -> RaopConnection(socket, this).run() }
        server = s
        return s.port
    }

    fun stop() {
        server?.close()
        server = null
        synchronized(this) { active }?.terminate()
    }

    fun update(transform: (NowPlayingState) -> NowPlayingState) = _state.update(transform)

    // ---- RaopHost ----------------------------------------------------------------------------

    override val hardwareAddress: ByteArray get() = identity.hardwareAddress

    override fun claim(connection: RaopConnection) {
        val previous = synchronized(this) { active.also { active = connection } }
        if (previous != null && previous !== connection) {
            Logs.rtsp.i("sender ${connection.senderAddress.hostAddress} takes over from ${previous.senderAddress.hostAddress}")
            previous.terminate()
        }
    }

    override fun release(connection: RaopConnection) {
        synchronized(this) {
            if (active !== connection) return
            active = null
        }
        audioFocus.release()
        onStreamingChanged(false)
        _state.value = NowPlayingState()
    }

    override fun onSenderInfo(connection: RaopConnection, name: String?) {
        if (isActive(connection)) _state.update { NowPlayingState(status = it.status, senderName = name, volume = it.volume) }
    }

    override fun onOutputChanged(connection: RaopConnection, playing: Boolean) {
        if (!isActive(connection)) return
        if (playing) {
            audioFocus.acquire()
            _state.update { it.copy(status = NowPlayingState.Status.Playing, positionAtElapsedMs = SystemClock.elapsedRealtime()) }
        } else {
            _state.update { if (it.status == NowPlayingState.Status.Playing) it.copy(status = NowPlayingState.Status.Paused) else it }
        }
        onStreamingChanged(playing)
    }

    override fun onPaused(connection: RaopConnection) {
        if (isActive(connection)) _state.update { it.copy(status = NowPlayingState.Status.Paused) }
    }

    override fun onVolume(connection: RaopConnection, volume: Double) {
        if (!isActive(connection)) return
        Logs.audio.i("volume $volume")
        _state.update { it.copy(volume = volume) }
        applyGain()
    }

    override fun currentVolume(): Double? = _state.value.volume

    override fun gain(): Float {
        if (forceMuted()) return 0f
        return Volume.gain(_state.value.volume ?: DEFAULT_VOLUME) * focusGain
    }

    override fun onParameters(connection: RaopConnection, contentType: String?, body: ByteArray) {
        if (isActive(connection)) parameterSink(contentType, body)
    }

    /** Debug: `run-as <pkg> touch files/dump_pcm` saves each session's decoded audio to files/dump.pcm. */
    override fun decodedTap(): ((ShortArray, Int) -> Unit)? {
        if (!File(context.filesDir, "dump_pcm").exists()) return null
        val out = java.io.BufferedOutputStream(java.io.FileOutputStream(File(context.filesDir, "dump.pcm")))
        val bytes = ByteArray(8192)
        Logs.audio.w("debug: dumping decoded PCM to files/dump.pcm")
        return { pcm, frames ->
            for (i in 0 until frames * 2) {
                bytes[i * 2] = pcm[i].toByte()
                bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
            }
            out.write(bytes, 0, frames * 4)
            out.flush()
        }
    }

    private fun applyGain() {
        val gain = gain()
        synchronized(this) { active }?.setGain(gain)
    }

    private fun isActive(connection: RaopConnection) = synchronized(this) { active === connection }

    /** Debug switch for silent testing: `adb shell run-as <pkg> touch files/force_mute`. */
    private fun forceMuted() = File(context.filesDir, "force_mute").exists()

    private companion object {
        const val DEFAULT_VOLUME = -15.0
    }
}
