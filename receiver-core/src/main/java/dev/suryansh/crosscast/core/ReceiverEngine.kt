package dev.suryansh.crosscast.core

import android.content.Context
import android.os.SystemClock
import dev.suryansh.crosscast.core.audio.AudioFocus
import dev.suryansh.crosscast.core.audio.Volume
import dev.suryansh.crosscast.core.audio.VolumeCurve
import dev.suryansh.crosscast.core.dacp.DacpClient
import dev.suryansh.crosscast.core.meta.MetadataHandler
import dev.suryansh.crosscast.core.meta.Progress
import dev.suryansh.crosscast.core.meta.TrackClock
import dev.suryansh.crosscast.core.raop.RaopConnection
import dev.suryansh.crosscast.core.raop.RaopHost
import dev.suryansh.crosscast.core.rtsp.RtspServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/**
 * The classic AirPlay receiver: RTSP server plus whichever sender is currently playing.
 * Exposes everything the UI needs as [state].
 */
class ReceiverEngine(private val context: Context, private val identity: ReceiverIdentity) : RaopHost {
    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    /** Called with true while audio is being output; the service raises its foreground type. */
    var onStreamingChanged: (Boolean) -> Unit = {}

    /** Called when a sender starts a stream (RECORD), before audio is due: time to wake the TV. */
    var onSessionStarted: () -> Unit = {}

    private var server: RtspServer? = null
    private var active: RaopConnection? = null
    private var dacp: DacpClient? = null
    private val metadata = MetadataHandler(update = { transform -> _state.update(transform) }, onProgress = ::onProgress)
    private val trackClock = TrackClock()
    private val prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    /** The last level played at (−30..0 dB), so a new session starts as loud as the last one ended. */
    @Volatile
    private var lastVolume: Double? = prefs.getFloat(KEY_VOLUME, Float.NaN).toDouble().takeUnless { it.isNaN() }

    /** This session's mapping from the sender's slider to the level played, set by its first volume. */
    @Volatile
    private var volumeCurve: VolumeCurve? = null
    private var focusGain = 1f

    /** User latency adjustment in ms, applied to the current and future sessions. */
    @Volatile
    var latencyOffsetMs: Int = 0
        set(value) {
            field = value
            Logs.audio.i("latency offset $value ms")
            synchronized(this) { active }?.setLatencyOffsetMs(value)
        }
    private val audioFocus = AudioFocus(context) { gain ->
        focusGain = gain
        applyGain()
    }

    val port: Int get() = server?.port ?: -1

    /** False if the RTSP server has died; the service then restarts the engine. */
    val isHealthy: Boolean get() = server?.isAlive == true

    /** Debug: simulates the server dying, to exercise the service's restart path. */
    fun debugKillServer() {
        Logs.rtsp.w("debug: closing the RTSP server")
        server?.close()
    }

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

    /**
     * Asks the current sender to play/pause, skip, etc. Returns the HTTP status, or null when no
     * sender is playing or it doesn't offer remote control.
     */
    suspend fun sendRemote(command: DacpClient.Command): Int? = dacpClient()?.send(command)

    /** Debug: any DACP request to the current sender, e.g. `getproperty?properties=dmcp.volume`. */
    suspend fun dacpRequest(path: String): DacpClient.Response? = dacpClient()?.request(path)

    private fun dacpClient(): DacpClient? = synchronized(this) {
        val connection = active ?: return null
        val id = connection.dacpId ?: return null
        val token = connection.activeRemote ?: return null
        dacp?.takeIf { it.dacpId == id } ?: DacpClient(context, id, token, connection.senderAddress).also { dacp = it }
    }

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
            dacp = null
        }
        audioFocus.release()
        onStreamingChanged(false)
        synchronized(trackClock) { trackClock.reset() }
        volumeCurve = null
        _state.value = NowPlayingState()
    }

    override fun onSenderInfo(connection: RaopConnection, name: String?) {
        if (!isActive(connection)) return
        synchronized(trackClock) { trackClock.reset() }
        volumeCurve = null
        _state.update { NowPlayingState(status = it.status, senderName = name, volume = it.volume) }
    }

    /**
     * Status follows what is heard: Buffering once audio arrives, Playing from the first frame with
     * sound, Paused once it stops: silence (iOS pauses by streaming silence), a FLUSH, or no audio.
     */
    override fun onOutputChanged(connection: RaopConnection, playing: Boolean) {
        if (!isActive(connection)) return
        if (playing) {
            audioFocus.acquire()
            _state.update { if (it.status == NowPlayingState.Status.Playing) it else it.copy(status = NowPlayingState.Status.Buffering) }
        } else {
            stopTrackClock()
        }
        onStreamingChanged(playing)
    }

    override fun onHeard(connection: RaopConnection, rtp: Int, atNs: Long) {
        if (!isActive(connection)) return
        val position = synchronized(trackClock) { trackClock.onHeard(rtp, elapsedMs(atNs)) }
        _state.update { it.withPosition(position).copy(status = NowPlayingState.Status.Playing) }
    }

    override fun onSilent(connection: RaopConnection, sinceNs: Long) {
        if (isActive(connection)) stopTrackClock(elapsedMs(sinceNs))
    }

    /** [System.nanoTime] → [SystemClock.elapsedRealtime], the clock the UI extrapolates on. */
    private fun elapsedMs(nanoTime: Long) = SystemClock.elapsedRealtime() + (nanoTime - System.nanoTime()) / 1_000_000

    override fun onRecord(connection: RaopConnection) {
        if (isActive(connection)) onSessionStarted()
    }

    override fun onPaused(connection: RaopConnection) {
        if (isActive(connection)) stopTrackClock()
    }

    private fun stopTrackClock(atMs: Long = SystemClock.elapsedRealtime()) {
        val position = synchronized(trackClock) { trackClock.onStopped(atMs) }
        _state.update {
            if (it.status == NowPlayingState.Status.Idle) it
            else it.withPosition(position).copy(status = NowPlayingState.Status.Paused)
        }
    }

    private fun onProgress(progress: Progress) {
        val position = synchronized(trackClock) { trackClock.onProgress(progress, SystemClock.elapsedRealtime()) }
        _state.update { it.withPosition(position) }
    }

    private fun NowPlayingState.withPosition(p: TrackClock.Position) =
        copy(positionMs = p.positionMs, positionAtElapsedMs = p.atMs, durationMs = p.durationMs ?: durationMs)

    override fun onVolume(connection: RaopConnection, volume: Double) {
        if (!isActive(connection)) return
        val curve = volumeCurve ?: VolumeCurve(opened = volume, anchor = lastVolume ?: volume).also { volumeCurve = it }
        val level = curve.effective(volume)
        Logs.audio.i("volume $volume → %.1f dB".format(Locale.US, level))
        _state.update { it.copy(volume = level) }
        // Mute isn't remembered: a new session shouldn't start silent.
        if (level >= Volume.MIN) {
            lastVolume = level
            prefs.edit().putFloat(KEY_VOLUME, level.toFloat()).apply()
        }
        applyGain()
    }

    override fun currentVolume(): Double? = _state.value.volume ?: lastVolume

    override fun latencyOffsetMs(): Int = latencyOffsetMs

    override fun gain(): Float {
        if (forceMuted()) return 0f
        return Volume.gain(_state.value.volume ?: lastVolume ?: DEFAULT_VOLUME) * focusGain
    }

    override fun onParameters(connection: RaopConnection, contentType: String?, body: ByteArray) {
        if (isActive(connection)) metadata.handle(contentType, body)
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
        const val KEY_VOLUME = "volume"
    }
}
