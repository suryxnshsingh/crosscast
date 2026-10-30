package dev.suryansh.airspeaker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver after a reboot or an app update, without the app being opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> AirPlayReceiverService.start(context, reason = "boot")
            Intent.ACTION_MY_PACKAGE_REPLACED -> AirPlayReceiverService.start(context, reason = "package-replaced")
        }
    }
}
