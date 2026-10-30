package dev.suryansh.airspeaker.service

import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.NowPlayingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide view of the receiver for the UI. The service publishes; activities observe. */
object ReceiverState {
    private val _nowPlaying = MutableStateFlow(NowPlayingState())
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    fun publish(state: NowPlayingState) {
        val previous = _nowPlaying.value
        _nowPlaying.value = state
        if (state.status != previous.status || state.senderName != previous.senderName) {
            Logs.ui.i("receiver ${state.status}${state.senderName?.let { " (from $it)" } ?: ""}")
        }
    }
}
