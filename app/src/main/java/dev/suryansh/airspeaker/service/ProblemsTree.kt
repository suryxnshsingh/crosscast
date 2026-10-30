package dev.suryansh.airspeaker.service

import android.util.Log
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Keeps recent warnings and errors for the status screen. */
class ProblemsTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int) = priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val detail = t?.message?.let { " ($it)" } ?: ""
        ReceiverState.recordProblem("$time ${tag ?: "app"}: $message$detail")
    }
}
