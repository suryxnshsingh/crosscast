package dev.suryansh.crosscast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.suryansh.crosscast.MainActivity
import dev.suryansh.crosscast.R
import dev.suryansh.crosscast.core.Logs
import dev.suryansh.crosscast.core.ReceiverEngine
import dev.suryansh.crosscast.core.ReceiverIdentity
import dev.suryansh.crosscast.core.mdns.RaopAdvertiser
import dev.suryansh.crosscast.core.mdns.RaopService
import dev.suryansh.crosscast.core.mdns.RegistrationFailedException
import dev.suryansh.crosscast.core.net.LocalNetwork
import dev.suryansh.crosscast.core.net.networkChanges
import dev.suryansh.crosscast.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Always-on AirPlay receiver.
 *
 * While idle the service is a `connectedDevice` foreground service; `mediaPlayback` is added only
 * while audio is streaming. Android 15+ refuses `mediaPlayback` services started from
 * BOOT_COMPLETED, and using the same path on every version keeps boot start tested on this
 * Android 14 TV too. See docs/notes.md.
 */
class AirPlayReceiverService : LifecycleService() {

    private lateinit var advertiser: RaopAdvertiser
    private var engine: ReceiverEngine? = null
    private var stateJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var streaming = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        enterForeground()

        multicastLock = getSystemService(WifiManager::class.java)
            .createMulticastLock("Crosscast")
            .apply {
                setReferenceCounted(false)
                acquire()
            }

        advertiser = RaopAdvertiser(this)
        val settings = SettingsRepository.from(this)
        lifecycleScope.launch {
            val identity = settings.identity.first()
            val port = MutableStateFlow(startEngine(identity))
            launch { superviseEngine(identity, port) }
            launch { settings.latencyOffsetMs.collect { engine?.latencyOffsetMs = it } }
            // Re-advertise whenever the network, the friendly name or the engine's port changes.
            combine(networkChanges(this@AirPlayReceiverService), settings.identity, port, ::Triple)
                .collectLatest { (network, identity, p) -> advertise(network, identity, p) }
        }
    }

    /** Restarts the engine if its RTSP server dies; re-advertising follows the new port. */
    private suspend fun superviseEngine(identity: ReceiverIdentity, port: MutableStateFlow<Int>) {
        while (true) {
            delay(SUPERVISE_INTERVAL_MS)
            if (engine?.isHealthy == true) continue
            Logs.rtsp.e("engine unhealthy; restarting")
            engine?.stop()
            port.value = startEngine(identity)
        }
    }

    private suspend fun startEngine(identity: ReceiverIdentity): Int {
        val e = ReceiverEngine(applicationContext, identity)
        e.latencyOffsetMs = SettingsRepository.from(this).latencyOffsetMs.first()
        e.onStreamingChanged = { streaming -> lifecycleScope.launch { setStreaming(streaming) } }
        e.onSessionStarted = { lifecycleScope.launch { onSessionStarted() } }
        engine = e
        ReceiverState.remote = e::sendRemote
        ReceiverState.debugKillServer = e::debugKillServer
        ReceiverState.debugDacp = e::dacpRequest
        stateJob?.cancel()
        stateJob = lifecycleScope.launch { e.state.collect { ReceiverState.publish(it) } }
        val port = withContext(Dispatchers.IO) { e.start(RaopService.RTSP_PORT) }
        ReceiverState.updateStatus { it.copy(running = true, port = port) }
        return port
    }

    /**
     * Wakes the TV and brings Now Playing to the front. Waking matters for sound too: while the
     * Chromecast sleeps, audio is routed to a dummy output instead of HDMI.
     */
    private suspend fun onSessionStarted() {
        if (!SettingsRepository.from(this).autoShowUi.first()) return
        if (!DebugFlags.noWake(this)) wakeScreen()
        try {
            MainActivity.showForSession(this)
        } catch (e: RuntimeException) {
            Logs.ui.w("could not open Now Playing; grant SYSTEM_ALERT_WINDOW (see docs/acceptance.md, Phase 6)", e)
        }
    }

    private fun wakeScreen() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        @Suppress("DEPRECATION") // still the way for a service to turn the screen on; needs TURN_SCREEN_ON
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Crosscast:session")
            .acquire(3_000)
        Logs.ui.i("woke the TV for a session (interactive=${power.isInteractive})")
    }

    private suspend fun advertise(network: LocalNetwork?, identity: ReceiverIdentity, port: Int) {
        Logs.mdns.i("default network: ${network?.ipv4?.hostAddress ?: "offline"} (${network?.interfaceName})")
        ReceiverState.updateStatus { it.copy(address = network?.ipv4?.hostAddress, registered = false, bonjourName = RaopService.serviceName(identity)) }
        if (network?.ipv4 == null) {
            advertiser.withdraw()
            return
        }
        while (true) {
            try {
                advertiser.advertise(identity, port, network.network)
                ReceiverState.updateStatus { it.copy(registered = true) }
                return
            } catch (e: RegistrationFailedException) {
                Logs.mdns.e("mDNS registration failed; retrying in 10 s", e)
                delay(10_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A null intent means the system restarted us after the process died.
        Logs.mdns.i("receiver service started (reason=${intent?.getStringExtra(EXTRA_REASON) ?: "sticky-restart"})")
        return START_STICKY
    }

    override fun onDestroy() {
        ReceiverState.remote = null
        ReceiverState.debugKillServer = null
        ReceiverState.debugDacp = null
        ReceiverState.updateStatus { it.copy(running = false, registered = false) }
        engine?.stop()
        advertiser.close()
        multicastLock?.release()
        Logs.mdns.i("receiver service destroyed")
        super.onDestroy()
    }

    /** Switches the foreground type between idle listening and active playback. */
    fun setStreaming(streaming: Boolean) {
        if (this.streaming == streaming) return
        this.streaming = streaming
        enterForeground()
    }

    private fun enterForeground() {
        val idleType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val type = if (streaming) idleType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else idleType
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: RuntimeException) {
            // Playback still works without the mediaPlayback type; we just keep the idle type.
            Logs.audio.w("could not switch foreground type to 0x${type.toString(16)}", e)
            if (type != idleType) ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), idleType)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_receiver),
            NotificationManager.IMPORTANCE_MIN,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speaker)
            .setContentTitle(getString(R.string.default_friendly_name))
            .setContentText(getString(if (streaming) R.string.notification_streaming else R.string.notification_idle))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_REASON = "reason"
        private const val SUPERVISE_INTERVAL_MS = 15_000L

        fun start(context: Context, reason: String) {
            val intent = Intent(context, AirPlayReceiverService::class.java).putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
