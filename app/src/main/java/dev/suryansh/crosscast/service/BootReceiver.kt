package dev.suryansh.crosscast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.suryansh.crosscast.core.Logs
import dev.suryansh.crosscast.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Starts the receiver after a reboot or an app update, without the app being opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // A quick local read; boot broadcasts allow several seconds.
                val enabled = runBlocking { SettingsRepository.from(context).startOnBoot.first() }
                if (enabled) AirPlayReceiverService.start(context, reason = "boot")
                else Logs.mdns.i("start on boot disabled")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AirPlayReceiverService.start(context, reason = "package-replaced")
        }
    }
}
