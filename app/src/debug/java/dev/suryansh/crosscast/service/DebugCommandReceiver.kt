package dev.suryansh.crosscast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.suryansh.crosscast.core.Logs
import dev.suryansh.crosscast.core.dacp.DacpClient
import dev.suryansh.crosscast.core.meta.Dmap
import dev.suryansh.crosscast.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug commands from adb: DACP commands (or any DACP request) to the current sender, or `kill_server` to test self-healing. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("command") ?: return
        val value = intent.getStringExtra("value")
        when (name) {
            "kill_server" -> return run { ReceiverState.debugKillServer?.invoke() }
            "dacp" -> {
                // Any DACP request, to probe what the sender supports: --es value "getspeakers"
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val r = ReceiverState.debugDacp?.invoke(value ?: "")
                        val dmap = r?.body?.takeIf { it.isNotEmpty() }?.let { Dmap.parse(it) }
                        Logs.meta.i("debug DACP $value → ${r?.status ?: "no sender"} ${dmap ?: ""}")
                    } finally {
                        pending.finish()
                    }
                }
                return
            }
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
