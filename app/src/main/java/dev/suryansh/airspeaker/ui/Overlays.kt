package dev.suryansh.airspeaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** AirPlay volume (−30..0 dB, −144 mute) as a 0..1 slider position. */
fun volumeFraction(volume: Double): Float =
    if (volume <= -30.0001) 0f else ((volume.coerceIn(-30.0, 0.0) + 30.0) / 30.0).toFloat()

@Composable
fun VolumeOverlay(volume: Double?, visible: Boolean, accent: Color, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible && volume != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val fraction = volumeFraction(volume ?: 0.0)
        Pill {
            Text(if (fraction == 0f) "Muted" else "Volume", color = AirSpeakerColors.TextSecondary, fontSize = 16.sp)
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .width(220.dp)
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(accent, RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.width(16.dp))
            Text("${(fraction * 100).toInt()}", color = AirSpeakerColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Brief confirmation of a remote-control press, e.g. "Next track". */
@Composable
fun ActionToast(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Pill { Text(message ?: "", color = AirSpeakerColors.TextPrimary, fontSize = 18.sp) }
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
