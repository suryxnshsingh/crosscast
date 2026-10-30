package dev.suryansh.airspeaker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.dacp.DacpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Sends a DACP command to the current sender from adb, for testing remote control without the UI. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("command") ?: return
        val command = DacpClient.Command.entries.firstOrNull { it.path == name } ?: run {
            Logs.meta.w("unknown DACP command $name")
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = ReceiverState.sendRemote(command)
                Logs.meta.i("debug DACP $name → ${status ?: "no sender"}")
            } finally {
                pending.finish()
            }
        }
    }
}
