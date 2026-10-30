package dev.suryansh.airspeaker.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.suryansh.airspeaker.core.Logs

/** Holds audio focus while a session is playing; reports the gain multiplier focus allows. */
class AudioFocus(context: Context, private val onGainChanged: (Float) -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var held = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            Logs.audio.i("audio focus change $change")
            onGainChanged(
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> 1f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.25f
                    else -> 0f
                }
            )
        }
        .build()

    @Synchronized
    fun acquire() {
        if (held) return
        held = true
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Logs.audio.i("audio focus requested, granted=$granted")
        onGainChanged(if (granted) 1f else 0f)
    }

    @Synchronized
    fun release() {
        if (!held) return
        held = false
        audioManager.abandonAudioFocusRequest(request)
        onGainChanged(1f)
    }
}
