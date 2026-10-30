package dev.suryansh.crosscast

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.suryansh.crosscast.core.ReceiverIdentity
import dev.suryansh.crosscast.service.ReceiverState
import dev.suryansh.crosscast.settings.SettingsRepository
import dev.suryansh.crosscast.ui.CrosscastTheme
import dev.suryansh.crosscast.ui.SettingsActions
import dev.suryansh.crosscast.ui.SettingsScreen
import dev.suryansh.crosscast.ui.SettingsValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Receiver status and settings. Opened with OK or MENU from the idle clock. */
class SettingsActivity : ComponentActivity() {
    private val canShowOverOtherApps = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.from(this)
        val values = combine(
            settings.identity, settings.autoShowUi, settings.idleTimeoutMs, settings.latencyOffsetMs, settings.startOnBoot, canShowOverOtherApps,
        ) { flows ->
            SettingsValues(
                friendlyName = (flows[0] as ReceiverIdentity).friendlyName,
                autoShowUi = flows[1] as Boolean,
                idleTimeoutS = ((flows[2] as Long) / 1000).toInt(),
                latencyOffsetMs = flows[3] as Int,
                startOnBoot = flows[4] as Boolean,
                canShowOverOtherApps = flows[5] as Boolean,
            )
        }
        val actions = object : SettingsActions {
            override fun setFriendlyName(name: String) = save { settings.setFriendlyName(name) }
            override fun setAutoShowUi(value: Boolean) = save { settings.setAutoShowUi(value) }
            override fun setIdleTimeoutS(value: Int) = save { settings.setIdleTimeoutSeconds(value) }
            override fun setLatencyOffsetMs(value: Int) = save { settings.setLatencyOffsetMs(value) }
            override fun setStartOnBoot(value: Boolean) = save { settings.setStartOnBoot(value) }
            override fun openSetup() = SetupActivity.open(this@SettingsActivity)
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        setContent {
            val status by ReceiverState.status.collectAsStateWithLifecycle()
            val problems by ReceiverState.problems.collectAsStateWithLifecycle()
            val current by remember { values }.collectAsStateWithLifecycle(initialValue = null)
            CrosscastTheme {
                current?.let { SettingsScreen(status, problems, it, actions, version) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canShowOverOtherApps.value = Settings.canDrawOverlays(this) // may have changed in the TV's Settings
    }

    private fun save(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }
}
