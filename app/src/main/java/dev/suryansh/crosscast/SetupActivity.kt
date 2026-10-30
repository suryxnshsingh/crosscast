package dev.suryansh.crosscast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.suryansh.crosscast.core.Logs
import dev.suryansh.crosscast.settings.SettingsRepository
import dev.suryansh.crosscast.ui.CrosscastTheme
import dev.suryansh.crosscast.ui.SetupScreen
import dev.suryansh.crosscast.ui.SetupStep

/**
 * Asks for "Display over other apps", the one permission that needs the user. It lets the receiver
 * bring Now Playing to the front when a session starts, and an activity coming to the front is
 * what wakes a sleeping TV. There's no dialog for it, so this sends the user to the TV's Settings.
 */
class SetupActivity : ComponentActivity() {
    private var step by mutableStateOf(SetupStep.Allow)
    private var noSettingsScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.from(this)
        setContent {
            val identity by settings.identity.collectAsStateWithLifecycle(initialValue = null)
            CrosscastTheme {
                SetupScreen(
                    step = step,
                    friendlyName = identity?.friendlyName ?: SettingsRepository.DEFAULT_FRIENDLY_NAME,
                    adbCommand = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
                    onOpenSettings = ::openOverlaySettings,
                    onDone = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // back from Settings: show what changed
    }

    private fun refresh() {
        step = when {
            Settings.canDrawOverlays(this) -> SetupStep.Done
            noSettingsScreen -> SetupStep.NoSettingsScreen
            else -> SetupStep.Allow
        }
        Logs.ui.i("setup: $step")
    }

    private fun openOverlaySettings() {
        val forThisApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val forAnyApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        for (intent in listOf(forThisApp, forAnyApp)) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Logs.ui.i("no settings screen for $intent")
            }
        }
        noSettingsScreen = true
        refresh()
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, SetupActivity::class.java))
    }
}
