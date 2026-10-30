package dev.suryansh.airspeaker

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.service.AirPlayReceiverService
import dev.suryansh.airspeaker.ui.AirSpeakerTheme
import dev.suryansh.airspeaker.ui.IdleScreen

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Logs.ui.i("notification permission granted=$granted")
            askToIgnoreBatteryOptimisations()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logs.ui.i("MainActivity created")
        AirPlayReceiverService.start(this, reason = "app")
        setContent {
            AirSpeakerTheme {
                IdleScreen(friendlyName = stringResource(R.string.default_friendly_name))
            }
        }
        askForPermissionsOnce()
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
}
