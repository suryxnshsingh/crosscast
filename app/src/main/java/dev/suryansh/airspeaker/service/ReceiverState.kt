package dev.suryansh.airspeaker.service

import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.NowPlayingState
import dev.suryansh.airspeaker.core.dacp.DacpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide view of the receiver for the UI. The service publishes; activities observe. */
object ReceiverState {
    private val _nowPlaying = MutableStateFlow(NowPlayingState())
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    /** Set by the service while the engine runs. */
    @Volatile
    internal var remote: (suspend (DacpClient.Command) -> Int?)? = null

    /** Remote-controls the sender (the iPhone). Returns the HTTP status, or null if unavailable. */
    suspend fun sendRemote(command: DacpClient.Command): Int? = remote?.invoke(command)

    fun publish(state: NowPlayingState) {
        val previous = _nowPlaying.value
        _nowPlaying.value = state
        if (state.status != previous.status || state.senderName != previous.senderName) {
            Logs.ui.i("receiver ${state.status}${state.senderName?.let { " (from $it)" } ?: ""}")
        }
    }
}
