package dev.suryansh.airspeaker.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.suryansh.airspeaker.core.NowPlayingState
import dev.suryansh.airspeaker.core.NowPlayingState.Status
import kotlinx.coroutines.delay

/**
 * Full-screen Now Playing in Apple's idiom: the cover art large with continuous corners, a
 * moving background made from its colours, white type at Apple's label opacities, and the art
 * settling back when paused, as in Apple Music.
 *
 * [motion] turns on the background motion and the live progress clock; screenshot tests switch
 * it off so frames are deterministic.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingState,
    modifier: Modifier = Modifier,
    showVolume: Boolean = false,
    toast: RemoteToast? = null,
    shift: Pair<Dp, Dp> = 0.dp to 0.dp,
    motion: Boolean = true,
) {
    val style = remember(state.artwork) { ArtworkStyle.from(state.artwork) }
    // A track skip is a FLUSH too, then Buffering once new audio arrives a moment later; only a
    // pause that lasts looks paused.
    val paused by produceState(state.status == Status.Paused && !motion, state.status) {
        value = if (state.status == Status.Paused) {
            if (motion) delay(PAUSE_DEBOUNCE_MS)
            true
        } else {
            false
        }
    }

    Box(modifier.fillMaxSize().background(AirSpeakerColors.Background)) {
        FluidBackground(style, motion, Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset(shift.first, shift.second)
                .padding(horizontal = 76.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkCard(state.artwork, paused)
            Spacer(Modifier.width(60.dp))
            Column(Modifier.weight(1f)) {
                SourceLine(state.senderName, paused)
                Spacer(Modifier.height(18.dp))
                TrackText(state.title, state.artist, state.album)
                Spacer(Modifier.height(40.dp))
                ProgressBar(state, live = motion && state.status == Status.Playing)
            }
        }

        // tvOS puts its HUDs in the top-right corner.
        VolumeHud(state.volume, showVolume, Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 40.dp))
        RemoteToastHud(toast, Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 40.dp))
    }
}

/** The cover, with a soft shadow; it eases back with a gentle spring while paused. */
@Composable
private fun ArtworkCard(artwork: Bitmap?, paused: Boolean) {
    val scale by animateFloatAsState(
        if (paused) PAUSED_SCALE else 1f,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessVeryLow * 2.5f),
        label = "artScale",
    )
    val shape = remember { ContinuousRoundedShape(14.dp) }
    Box(
        Modifier
            .size(ART_SIZE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // Lifted while playing, closer to the background when paused.
                val lift = ((scale - PAUSED_SCALE) / (1f - PAUSED_SCALE)).coerceIn(0f, 1f)
                shadowElevation = (10 + 26 * lift).dp.toPx()
                this.shape = shape
                clip = true
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .border(1.dp, AirSpeakerColors.Hairline, shape),
    ) {
        Crossfade(artwork, animationSpec = tween(700), label = "artwork") { art ->
            if (art != null) {
                Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            } else {
                // Apple Music's empty-artwork tile: graphite with a note.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF545458), Color(0xFF2C2C2E)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(140.dp))
                }
            }
        }
    }
}

/** Where the audio comes from, or that it's paused. */
@Composable
private fun SourceLine(senderName: String?, paused: Boolean) {
    Crossfade(paused, animationSpec = tween(300), label = "source") { isPaused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (glyph, text) = if (isPaused) Glyphs.Pause to "Paused" else Glyphs.AirPlayAudio to (senderName ?: "AirPlay")
            Icon(glyph, contentDescription = null, tint = AirSpeakerColors.TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                color = AirSpeakerColors.TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackText(title: String?, artist: String?, album: String?) {
    AnimatedContent(
        targetState = Triple(title, artist, album),
        transitionSpec = { fadeIn(tween(500, delayMillis = 150)) togetherWith fadeOut(tween(250)) },
        label = "track",
    ) { (title, artist, album) ->
        Column {
            Text(
                text = title ?: "Not Playing",
                color = AirSpeakerColors.TextPrimary,
                style = TextStyle(fontFamily = InterDisplay, letterSpacing = (-0.02).em),
                fontSize = 40.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    artist,
                    color = AirSpeakerColors.TextSecondary,
                    style = TextStyle(fontFamily = InterDisplay, letterSpacing = (-0.01).em),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (album != null) {
                Spacer(Modifier.height(6.dp))
                Text(album, color = AirSpeakerColors.TextTertiary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Thin white bar with elapsed and remaining time; frozen unless audio is being heard. */
@Composable
private fun ProgressBar(state: NowPlayingState, live: Boolean) {
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
    val position = state.positionMsAt(if (live) now else state.positionAtElapsedMs) ?: 0
    val fraction = position.toFloat() / duration

    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.White.copy(alpha = 0.2f), CircleShape)
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Color.White.copy(alpha = 0.9f), CircleShape))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        val digits = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum")
        Text(clock(position), color = AirSpeakerColors.TextTertiary, fontSize = 15.sp, fontWeight = FontWeight.Medium, style = digits)
        Spacer(Modifier.weight(1f))
        Text("−" + clock(duration - position), color = AirSpeakerColors.TextTertiary, fontSize = 15.sp, fontWeight = FontWeight.Medium, style = digits)
    }
}

private val ART_SIZE = 384.dp
private const val PAUSED_SCALE = 0.84f
private const val PAUSE_DEBOUNCE_MS = 1_200L

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
