package dev.suryansh.airspeaker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.dacp.DacpClient
import dev.suryansh.airspeaker.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug commands from adb: DACP commands to the current sender, or `kill_server` to test self-healing. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("command") ?: return
        val value = intent.getStringExtra("value")
        when (name) {
            "kill_server" -> return run { ReceiverState.debugKillServer?.invoke() }
            "set_name", "latency" -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = SettingsRepository.from(context)
                        if (name == "set_name") settings.setFriendlyName(value ?: "") else settings.setLatencyOffsetMs(value?.toIntOrNull() ?: 0)
                        Logs.ui.i("debug: $name = $value")
                    } finally {
                        pending.finish()
                    }
                }
                return
            }
        }
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
