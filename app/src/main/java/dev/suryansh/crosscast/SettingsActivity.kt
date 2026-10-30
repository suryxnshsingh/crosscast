package dev.suryansh.crosscast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.suryansh.crosscast.service.ReceiverState
import dev.suryansh.crosscast.settings.SettingsRepository
import dev.suryansh.crosscast.ui.CrosscastTheme
import dev.suryansh.crosscast.ui.SettingsActions
import dev.suryansh.crosscast.ui.SettingsScreen
import dev.suryansh.crosscast.ui.SettingsValues
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Receiver status and settings. Opened with OK or MENU from the idle clock. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.from(this)
        val values = combine(
            settings.identity, settings.autoShowUi, settings.idleTimeoutMs, settings.latencyOffsetMs, settings.startOnBoot,
        ) { identity, autoShow, idleMs, latency, boot ->
            SettingsValues(identity.friendlyName, autoShow, (idleMs / 1000).toInt(), latency, boot)
        }
        val actions = object : SettingsActions {
            override fun setFriendlyName(name: String) = save { settings.setFriendlyName(name) }
            override fun setAutoShowUi(value: Boolean) = save { settings.setAutoShowUi(value) }
            override fun setIdleTimeoutS(value: Int) = save { settings.setIdleTimeoutSeconds(value) }
            override fun setLatencyOffsetMs(value: Int) = save { settings.setLatencyOffsetMs(value) }
            override fun setStartOnBoot(value: Boolean) = save { settings.setStartOnBoot(value) }
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

    private fun save(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }
}
