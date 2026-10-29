package dev.suryansh.airspeaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.res.stringResource
import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.ui.AirSpeakerTheme
import dev.suryansh.airspeaker.ui.IdleScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logs.ui.i("MainActivity created")
        setContent {
            AirSpeakerTheme {
                IdleScreen(friendlyName = stringResource(R.string.default_friendly_name))
            }
        }
    }
}
