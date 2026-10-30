package dev.suryansh.airspeaker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import dev.suryansh.airspeaker.R

/** Apple's dark-mode system colours: white labels at fixed opacities, system blue for focus. */
object AirSpeakerColors {
    val Background = Color(0xFF000000)
    val Accent = Color(0xFF0A84FF)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0x99EBEBF5) // secondaryLabel: 60 %
    val TextTertiary = Color(0x66EBEBF5) // between tertiaryLabel (30 %) and secondary, legible over artwork
    /** The translucent dark material behind HUDs. */
    val Material = Color(0xC71C1C1E)
    val Hairline = Color(0x1AFFFFFF)
}

/**
 * Inter, the closest open font to Apple's SF Pro (which may only be used on Apple platforms).
 * Inter Display is its optical size for large text, as SF Pro Display is for SF Pro Text.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val InterDisplay = FontFamily(
    Font(R.font.inter_display_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

/** SF-like spacing: tight for display sizes, neutral for text (Material's defaults are looser). */
private fun TextStyle.display() = copy(fontFamily = InterDisplay, letterSpacing = (-0.015).em)
private fun TextStyle.text() = copy(fontFamily = Inter, letterSpacing = (-0.005).em)

private val AppTypography = Typography().let {
    Typography(
        displayLarge = it.displayLarge.display(),
        displayMedium = it.displayMedium.display(),
        displaySmall = it.displaySmall.display(),
        headlineLarge = it.headlineLarge.display(),
        headlineMedium = it.headlineMedium.display(),
        headlineSmall = it.headlineSmall.display(),
        titleLarge = it.titleLarge.text(),
        titleMedium = it.titleMedium.text(),
        titleSmall = it.titleSmall.text(),
        bodyLarge = it.bodyLarge.text(),
        bodyMedium = it.bodyMedium.text(),
        bodySmall = it.bodySmall.text(),
        labelLarge = it.labelLarge.text(),
        labelMedium = it.labelMedium.text(),
        labelSmall = it.labelSmall.text(),
    )
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
        typography = AppTypography,
        content = content,
    )
}
