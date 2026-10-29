package dev.suryansh.airspeaker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.suryansh.airspeaker.ui.AirSpeakerTheme
import dev.suryansh.airspeaker.ui.IdleScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders screens at the TV's real configuration: 1920x1080 px at 320 dpi = 960x540 dp. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class IdleScreenScreenshotTest {
    @Test
    fun idleScreen() {
        captureRoboImage("src/test/screenshots/idle_screen.png") {
            AirSpeakerTheme { IdleScreen(friendlyName = "Room Speaker") }
        }
    }
}
