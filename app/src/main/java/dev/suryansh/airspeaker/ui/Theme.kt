package dev.suryansh.airspeaker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object AirSpeakerColors {
    val Background = Color(0xFF0A0C10)
    val Accent = Color(0xFFFF9E59)
    val TextPrimary = Color(0xFFF2F4F7)
    val TextSecondary = Color(0x99F2F4F7)
}

@Composable
fun AirSpeakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AirSpeakerColors.Accent,
            background = AirSpeakerColors.Background,
            surface = AirSpeakerColors.Background,
            onBackground = AirSpeakerColors.TextPrimary,
            onSurface = AirSpeakerColors.TextPrimary,
        ),
        content = content,
    )
}
