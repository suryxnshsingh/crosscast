package dev.suryansh.airspeaker.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.suryansh.airspeaker.core.NowPlayingState
import kotlinx.coroutines.delay

/**
 * Full-screen Now Playing. [motion] turns on the slow background drift and the live progress
 * clock; screenshot tests switch it off so frames are deterministic.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingState,
    modifier: Modifier = Modifier,
    showVolume: Boolean = false,
    toast: String? = null,
    shift: Pair<Dp, Dp> = 0.dp to 0.dp,
    motion: Boolean = true,
) {
    val style = remember(state.artwork) { ArtworkStyle.from(state.artwork) }
    val accent by animateColorAsState(style.accent, tween(800), label = "accent")
    val paused = state.status == NowPlayingState.Status.Paused

    Box(modifier.fillMaxSize().background(AirSpeakerColors.Background)) {
        Backdrop(style, motion)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset(shift.first, shift.second)
                .padding(horizontal = 80.dp, vertical = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artAlpha by animateFloatAsState(if (paused) 0.6f else 1f, tween(400), label = "artAlpha")
            Crossfade(state.artwork, animationSpec = tween(800), label = "artwork") { art ->
                ArtworkCard(art, accent, Modifier.alpha(artAlpha))
            }
            Spacer(Modifier.width(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    state.senderName?.let { SenderBadge(it, accent) }
                    if (paused) {
                        if (state.senderName != null) Spacer(Modifier.width(12.dp))
                        Chip("Paused")
                    }
                }
                Spacer(Modifier.height(24.dp))
                AnimatedContent(
                    targetState = Triple(state.title, state.artist, state.album),
                    transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) },
                    label = "track",
                ) { (title, artist, album) ->
                    Column {
                        Text(
                            text = title ?: "AirPlay",
                            color = AirSpeakerColors.TextPrimary,
                            fontSize = 40.sp,
                            lineHeight = 46.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artist != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(artist, color = AirSpeakerColors.TextPrimary.copy(alpha = 0.85f), fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (album != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(album, color = AirSpeakerColors.TextSecondary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
                Progress(state, accent, live = motion && !paused)
            }
        }

        VolumeOverlay(state.volume, showVolume, accent, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
        ActionToast(toast, Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
    }
}

/** The cover art, blurred and darkened, drifting slowly (Ken Burns). */
@Composable
private fun Backdrop(style: ArtworkStyle, motion: Boolean) {
    val drift = if (motion) rememberDrift() else null

    Crossfade(style.backdrop, animationSpec = tween(1200), label = "backdrop") { backdrop ->
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read inside the layer so the drift redraws without recomposing.
                    val s = drift?.scale?.value ?: 1.2f
                    scaleX = s
                    scaleY = s
                    translationX = (drift?.dx?.value ?: 0f) * size.width
                    translationY = (drift?.dy?.value ?: 0f) * size.height
                }
        ) {
            if (backdrop != null) {
                Image(backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2638), Color(0xFF2A1B38), Color(0xFF0A0C10)))))
            }
        }
    }
    // Darken for legibility, more on the text side.
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.7f))))
    )
}

private class Drift(val scale: State<Float>, val dx: State<Float>, val dy: State<Float>)

@Composable
private fun rememberDrift(): Drift {
    val t = rememberInfiniteTransition(label = "drift")
    return Drift(
        scale = t.animateFloat(1.15f, 1.3f, infiniteRepeatable(tween(37_000, easing = LinearEasing), RepeatMode.Reverse), label = "scale"),
        dx = t.animateFloat(-0.04f, 0.04f, infiniteRepeatable(tween(53_000, easing = LinearEasing), RepeatMode.Reverse), label = "dx"),
        dy = t.animateFloat(-0.03f, 0.03f, infiniteRepeatable(tween(41_000, easing = LinearEasing), RepeatMode.Reverse), label = "dy"),
    )
}

@Composable
private fun ArtworkCard(artwork: Bitmap?, accent: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .size(360.dp)
            .shadow(36.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        } else {
            // No artwork: a gradient tile in the accent colour.
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), Color(0xFF1B2638)))))
            Text("♪", color = Color.White.copy(alpha = 0.8f), fontSize = 120.sp)
        }
    }
}

@Composable
private fun SenderBadge(name: String, accent: Color) {
    Row(
        Modifier
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("from $name", color = AirSpeakerColors.TextSecondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text.uppercase(),
        color = AirSpeakerColors.TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Progress(state: NowPlayingState, accent: Color, live: Boolean) {
    val duration = state.durationMs ?: return
    if (duration <= 0) return
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (live) {
        LaunchedEffect(state.positionAtElapsedMs) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(250)
            }
        }
    }
    val base = state.positionMs ?: 0
    val position = (if (live) base + (now - state.positionAtElapsedMs) else base).coerceIn(0, duration)
    val fraction = position.toFloat() / duration

    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent, RoundedCornerShape(3.dp)))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        val digits = TextStyle(fontFeatureSettings = "tnum")
        Text(clock(position), color = AirSpeakerColors.TextSecondary, fontSize = 18.sp, style = digits)
        Spacer(Modifier.weight(1f))
        Text("−" + clock(duration - position), color = AirSpeakerColors.TextSecondary, fontSize = 18.sp, style = digits)
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
