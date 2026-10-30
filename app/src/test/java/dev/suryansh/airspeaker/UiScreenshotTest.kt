package dev.suryansh.airspeaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.suryansh.airspeaker.core.NowPlayingState
import dev.suryansh.airspeaker.core.NowPlayingState.Status
import dev.suryansh.airspeaker.ui.AirSpeakerTheme
import dev.suryansh.airspeaker.ui.IdleScreen
import dev.suryansh.airspeaker.service.ReceiverStatus
import dev.suryansh.airspeaker.ui.NowPlayingScreen
import dev.suryansh.airspeaker.ui.SettingsActions
import dev.suryansh.airspeaker.ui.SettingsScreen
import dev.suryansh.airspeaker.ui.SettingsValues
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** Renders every screen at the TV's real configuration: 1920x1080 px at 320 dpi = 960x540 dp. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class UiScreenshotTest {
    private val playing = NowPlayingState(
        status = Status.Playing,
        senderName = "Suryansh’s iPhone",
        title = "Midnight City",
        artist = "M83",
        album = "Hurry Up, We’re Dreaming",
        artwork = sampleArtwork(),
        positionMs = 83_000,
        durationMs = 243_000,
        volume = -12.0,
    )

    @Test
    fun nowPlaying() = capture("now_playing") { NowPlayingScreen(playing, motion = false) }

    @Test
    fun nowPlayingWithoutArtwork() = capture("now_playing_no_artwork") {
        NowPlayingScreen(
            playing.copy(artwork = null, title = "A Very Long Track Title That Has To Wrap Onto A Second Line Somewhere", album = null),
            motion = false,
        )
    }

    @Test
    fun paused() = capture("now_playing_paused") { NowPlayingScreen(playing.copy(status = Status.Paused), motion = false) }

    @Test
    fun volumeOverlay() = capture("now_playing_volume") { NowPlayingScreen(playing, showVolume = true, motion = false) }

    @Test
    fun remoteToast() = capture("now_playing_toast") { NowPlayingScreen(playing, toast = "Next track", motion = false) }

    @Test
    fun idleClock() = capture("idle_clock") {
        IdleScreen("Room Speaker", fixedTimeMs = 1_793_318_400_000L, zone = ZoneId.of("Asia/Kolkata"))
    }

    @Test
    fun settings() = capture("settings") {
        SettingsScreen(
            status = ReceiverStatus(
                running = true, address = "192.168.29.48", port = 5000, bonjourName = "1E2E5EB64A10@Room Speaker",
                registered = true, lastSender = "Suryansh’s iPhone", lastSessionAtMs = 1_793_318_400_000L,
            ),
            problems = listOf("05:51:18 audio: sync error -56 ms; re-aligning", "05:49:44 rtsp: port 5000 is busy; using a free port"),
            values = SettingsValues("Room Speaker", autoShowUi = true, idleTimeoutS = 180, latencyOffsetMs = 20, startOnBoot = true),
            actions = object : SettingsActions {
                override fun setFriendlyName(name: String) = Unit
                override fun setAutoShowUi(value: Boolean) = Unit
                override fun setIdleTimeoutS(value: Int) = Unit
                override fun setLatencyOffsetMs(value: Int) = Unit
                override fun setStartOnBoot(value: Boolean) = Unit
            },
            version = "0.1.0",
            zone = ZoneId.of("Asia/Kolkata"),
        )
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png") { AirSpeakerTheme { content() } }

    /** A colourful stand-in cover: dusk gradient, a sun and a skyline. */
    private fun sampleArtwork(): Bitmap {
        val size = 1000
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), intArrayOf(0xFF1A1446.toInt(), 0xFF7A2C8F.toInt(), 0xFFF06A4B.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = RadialGradient(500f, 640f, 220f, 0xFFFFD27A.toInt(), 0x00FFD27A, Shader.TileMode.CLAMP)
        canvas.drawCircle(500f, 640f, 220f, paint)
        paint.shader = null
        paint.color = 0xFF120C2A.toInt()
        val heights = intArrayOf(260, 380, 300, 460, 340, 420, 280, 360, 320)
        val w = size / heights.size.toFloat()
        heights.forEachIndexed { i, h -> canvas.drawRect(i * w, (size - h).toFloat(), (i + 1) * w - 6, size.toFloat(), paint) }
        return bitmap
    }
}
