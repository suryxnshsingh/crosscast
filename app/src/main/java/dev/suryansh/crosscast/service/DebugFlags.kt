package dev.suryansh.crosscast.service

import android.content.Context
import java.io.File

/**
 * Switches for testing without disturbing anyone, toggled by creating files in the app's data:
 * `adb shell "run-as dev.suryansh.crosscast touch files/no_wake"`.
 */
object DebugFlags {
    /** Don't wake the TV when a session starts (the UI still comes to the front). */
    fun noWake(context: Context) = File(context.filesDir, "no_wake").exists()
}
