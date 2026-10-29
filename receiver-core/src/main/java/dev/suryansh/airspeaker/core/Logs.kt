package dev.suryansh.airspeaker.core

import timber.log.Timber

/**
 * One logger per subsystem so logcat can be filtered by tag:
 * `adb logcat -s mdns rtsp audio meta ui`.
 */
object Logs {
    val mdns = TaggedLog("mdns")
    val rtsp = TaggedLog("rtsp")
    val audio = TaggedLog("audio")
    val meta = TaggedLog("meta")
    val ui = TaggedLog("ui")
}

class TaggedLog(val tag: String) {
    fun v(message: String) = Timber.tag(tag).v(message)
    fun d(message: String) = Timber.tag(tag).d(message)
    fun i(message: String) = Timber.tag(tag).i(message)
    fun w(message: String, t: Throwable? = null) = Timber.tag(tag).w(t, message)
    fun e(message: String, t: Throwable? = null) = Timber.tag(tag).e(t, message)
}
