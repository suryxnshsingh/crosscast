package dev.suryansh.crosscast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Dim clock shown while nothing is playing: readable across the room, kind to the panel. */
@Composable
fun IdleScreen(
    friendlyName: String,
    modifier: Modifier = Modifier,
    use24Hour: Boolean = true,
    shift: Pair<Dp, Dp> = 0.dp to 0.dp,
    fixedTimeMs: Long? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var now by remember { mutableLongStateOf(fixedTimeMs ?: System.currentTimeMillis()) }
    if (fixedTimeMs == null) {
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000 - now % 1_000)
            }
        }
    }
    val time = Instant.ofEpochMilli(now).atZone(zone)
    val timeText = time.format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm"))
    val dateText = time.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CrosscastColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(shift.first, shift.second)) {
            Text(
                text = timeText,
                color = CrosscastColors.TextPrimary.copy(alpha = 0.75f),
                fontSize = 128.sp,
                fontWeight = FontWeight.Light,
                style = TextStyle(fontFamily = InterDisplay, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em),
            )
            Text(dateText, color = CrosscastColors.TextSecondary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(48.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Cast, contentDescription = null, tint = CrosscastColors.TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("$friendlyName · Ready for AirPlay", color = CrosscastColors.TextSecondary.copy(alpha = 0.5f), fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("OK for settings", color = CrosscastColors.TextSecondary.copy(alpha = 0.3f), fontSize = 13.sp)
        }
    }
}
