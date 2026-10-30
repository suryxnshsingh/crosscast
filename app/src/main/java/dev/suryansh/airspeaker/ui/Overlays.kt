package dev.suryansh.airspeaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/** AirPlay volume (−30..0 dB, −144 mute) as a 0..1 slider position. */
fun volumeFraction(volume: Double): Float =
    if (volume <= -30.0001) 0f else ((volume.coerceIn(-30.0, 0.0) + 30.0) / 30.0).toFloat()

/** A remote-control press being confirmed, e.g. "Next" with its symbol. */
data class RemoteToast(val text: String, val glyph: ImageVector? = null)

/** tvOS-style volume HUD: a small capsule in the corner while the sender changes the volume. */
@Composable
fun VolumeHud(volume: Double?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible && volume != null,
        enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.92f),
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.96f),
        modifier = modifier,
    ) {
        val fraction = volumeFraction(volume ?: 0.0)
        Capsule {
            Icon(
                if (fraction == 0f) Glyphs.SpeakerMuted else Glyphs.Speaker,
                contentDescription = if (fraction == 0f) "Muted" else "Volume",
                tint = AirSpeakerColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(160.dp)
                    .height(5.dp)
                    .background(Color.White.copy(alpha = 0.22f), CircleShape)
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(AirSpeakerColors.TextPrimary, CircleShape))
            }
        }
    }
}

/** Brief confirmation of a remote-control press, at the top of the screen. */
@Composable
fun RemoteToastHud(toast: RemoteToast?, modifier: Modifier = Modifier) {
    // Keep showing the last toast while it fades out.
    val last = remember { mutableStateOf(toast) }
    if (toast != null) last.value = toast
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.92f),
        exit = fadeOut(tween(300)),
        modifier = modifier,
    ) {
        val shown = last.value ?: return@AnimatedVisibility
        Capsule {
            shown.glyph?.let {
                Icon(it, contentDescription = null, tint = AirSpeakerColors.TextPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(shown.text, color = AirSpeakerColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** The translucent dark material Apple uses for HUDs, with a hairline edge. */
@Composable
private fun Capsule(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .background(AirSpeakerColors.Material, CircleShape)
            .border(1.dp, AirSpeakerColors.Hairline, CircleShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
