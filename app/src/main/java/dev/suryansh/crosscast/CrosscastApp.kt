package dev.suryansh.crosscast

import android.app.Application
import dev.suryansh.crosscast.service.ProblemsTree
import timber.log.Timber

class CrosscastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Personal sideloaded app: always log to logcat, release builds included.
        Timber.plant(Timber.DebugTree())
        Timber.plant(ProblemsTree())
    }
}
