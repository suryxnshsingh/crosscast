package dev.suryansh.airspeaker

import android.app.Application
import timber.log.Timber

class AirSpeakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Personal sideloaded app: always log to logcat, release builds included.
        Timber.plant(Timber.DebugTree())
    }
}
