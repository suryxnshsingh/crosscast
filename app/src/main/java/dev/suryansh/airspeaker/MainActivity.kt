package dev.suryansh.airspeaker

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.NowPlayingState.Status
import dev.suryansh.airspeaker.core.dacp.DacpClient
import dev.suryansh.airspeaker.service.AirPlayReceiverService
import dev.suryansh.airspeaker.service.DebugFlags
import dev.suryansh.airspeaker.service.ReceiverState
import dev.suryansh.airspeaker.settings.SettingsRepository
import dev.suryansh.airspeaker.ui.AirSpeakerTheme
import dev.suryansh.airspeaker.ui.IdleScreen
import dev.suryansh.airspeaker.ui.NowPlayingScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Now Playing while a sender is connected, a dim clock otherwise. The receiver service brings it
 * to the front when a session starts ([EXTRA_AUTO]); then it closes itself once the session has
 * been over for the idle timeout, returning to whatever was on screen before.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Logs.ui.i("notification permission granted=$granted")
            askToIgnoreBatteryOptimisations()
        }

    private var autoLaunched = false
    private var toast by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoLaunched = intent.getBooleanExtra(EXTRA_AUTO, false)
        Logs.ui.i("MainActivity created (auto=$autoLaunched)")
        setShowWhenLocked(true)
        setTurnScreenOn(!DebugFlags.noWake(this))
        AirPlayReceiverService.start(this, reason = "app")

        val settings = SettingsRepository.from(this)
        setContent {
            val state by ReceiverState.nowPlaying.collectAsStateWithLifecycle()
            val identity by settings.identity.collectAsStateWithLifecycle(initialValue = null)
            val shift = rememberBurnInShift()

            // Show the volume overlay for a moment whenever the sender changes the volume.
            var volumeShownAt by remember { mutableStateOf(0L) }
            var lastVolume by remember { mutableStateOf(state.volume) }
            LaunchedEffect(state.volume) {
                if (state.volume != null && lastVolume != null && state.volume != lastVolume) volumeShownAt = SystemClock.elapsedRealtime()
                lastVolume = state.volume
                delay(VOLUME_OVERLAY_MS)
                volumeShownAt = 0
            }

            LaunchedEffect(state.status) {
                // Keep the screen on only while audio plays; afterwards the TV's own screensaver applies.
                if (state.status == Status.Playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            AirSpeakerTheme {
                if (state.status == Status.Idle) {
                    IdleScreen(
                        friendlyName = identity?.friendlyName ?: SettingsRepository.DEFAULT_FRIENDLY_NAME,
                        use24Hour = DateFormat.is24HourFormat(this),
                        shift = shift,
                    )
                } else {
                    NowPlayingScreen(state, showVolume = volumeShownAt != 0L, toast = toast, shift = shift)
                }
            }
        }
        askForPermissionsOnce()

        // Outside composition so it also runs while the activity is stopped (e.g. the TV went to sleep).
        lifecycleScope.launch {
            ReceiverState.nowPlaying.map { it.status }.distinctUntilChanged().collectLatest { status ->
                if (status != Status.Idle || !autoLaunched) return@collectLatest
                val timeoutMs = settings.idleTimeoutMs.first()
                delay(timeoutMs)
                Logs.ui.i("idle for ${timeoutMs / 1000} s; returning to the previous screen")
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) autoLaunched = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (ReceiverState.nowPlaying.value.status == Status.Idle) {
            if (keyCode in SETTINGS_KEYS) {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        val (command, label) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> DacpClient.Command.PlayPause to "Play / Pause"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> DacpClient.Command.Next to "Next track"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> DacpClient.Command.Previous to "Previous track"
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount > 0) return true
        lifecycleScope.launch {
            showToast(label)
            val status = ReceiverState.sendRemote(command)
            Logs.ui.i("remote ${command.path} → ${status ?: "unavailable"}")
            if (status == null || status !in 200..299) showToast("Remote control unavailable")
        }
        return true
    }

    private suspend fun showToast(message: String) {
        toast = message
        delay(1_200)
        if (toast == message) toast = null
    }

    /** First launch only: the notification permission, then the battery-optimisation exemption. */
    private fun askForPermissionsOnce() {
        val prefs = getSharedPreferences("first_run", MODE_PRIVATE)
        if (prefs.getBoolean("permissions_asked", false)) return
        prefs.edit { putBoolean("permissions_asked", true) }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askToIgnoreBatteryOptimisations()
        }
    }

    @SuppressLint("BatteryLife") // An always-on receiver is the use case this exemption exists for.
    private fun askToIgnoreBatteryOptimisations() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            // Google TV on the Chromecast has no such screen; use `cmd deviceidle whitelist` instead.
            Logs.ui.i("battery optimisation settings not available on this device")
        }
    }

    companion object {
        /** Set when the receiver service opens the activity for a new session. */
        const val EXTRA_AUTO = "auto"
        private const val VOLUME_OVERLAY_MS = 2_000L
        private val SETTINGS_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU)

        fun showForSession(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_AUTO, true)
            )
        }
    }
}

/** Moves the layout a few pixels each minute so nothing sits still on the panel for hours. */
@Composable
private fun rememberBurnInShift(): Pair<Dp, Dp> {
    val offsets = remember { listOf(0 to 0, 4 to 2, -3 to 4, 2 to -4, -4 to -2, 3 to 3) }
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            index = (index + 1) % offsets.size
        }
    }
    val x by animateDpAsState(offsets[index].first.dp, tween(2_000), label = "shiftX")
    val y by animateDpAsState(offsets[index].second.dp, tween(2_000), label = "shiftY")
    return x to y
}
