package dev.suryansh.crosscast.service

import dev.suryansh.crosscast.core.Logs
import dev.suryansh.crosscast.core.NowPlayingState
import dev.suryansh.crosscast.core.dacp.DacpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Health of the receiver, for the status screen. */
data class ReceiverStatus(
    val running: Boolean = false,
    val address: String? = null,
    val port: Int? = null,
    val bonjourName: String? = null,
    val registered: Boolean = false,
    val lastSender: String? = null,
    val lastSessionAtMs: Long? = null,
)

/** Process-wide view of the receiver for the UI. The service publishes; activities observe. */
object ReceiverState {
    private val _nowPlaying = MutableStateFlow(NowPlayingState())
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val _problems = MutableStateFlow<List<String>>(emptyList())

    /** The most recent warnings and errors from any subsystem, newest first. */
    val problems: StateFlow<List<String>> = _problems.asStateFlow()

    /** Set by the service while the engine runs. */
    @Volatile
    internal var remote: (suspend (DacpClient.Command) -> Int?)? = null

    /** Debug builds: closes the RTSP server to test the supervisor's restart. */
    @Volatile
    internal var debugKillServer: (() -> Unit)? = null
    internal var debugDacp: (suspend (String) -> DacpClient.Response?)? = null

    /** Remote-controls the sender (the iPhone). Returns the HTTP status, or null if unavailable. */
    suspend fun sendRemote(command: DacpClient.Command): Int? = remote?.invoke(command)

    fun publish(state: NowPlayingState) {
        val previous = _nowPlaying.value
        _nowPlaying.value = state
        if (state.status != previous.status || state.senderName != previous.senderName) {
            Logs.ui.i("receiver ${state.status}${state.senderName?.let { " (from $it)" } ?: ""}")
        }
        if (previous.status == NowPlayingState.Status.Idle && state.status != NowPlayingState.Status.Idle) {
            _status.update { it.copy(lastSender = state.senderName ?: "Unknown sender", lastSessionAtMs = System.currentTimeMillis()) }
        }
    }

    fun updateStatus(transform: (ReceiverStatus) -> ReceiverStatus) = _status.update(transform)

    fun recordProblem(line: String) = _problems.update { (listOf(line) + it).take(MAX_PROBLEMS) }

    private const val MAX_PROBLEMS = 8
}
