package dev.suryansh.crosscast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import dev.suryansh.crosscast.service.ReceiverStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SettingsValues(
    val friendlyName: String,
    val autoShowUi: Boolean,
    val idleTimeoutS: Int,
    val latencyOffsetMs: Int,
    val startOnBoot: Boolean,
)

interface SettingsActions {
    fun setFriendlyName(name: String)
    fun setAutoShowUi(value: Boolean)
    fun setIdleTimeoutS(value: Int)
    fun setLatencyOffsetMs(value: Int)
    fun setStartOnBoot(value: Boolean)
}

val IDLE_TIMEOUT_CHOICES = listOf(30, 60, 180, 600, 1800)

@Composable
fun SettingsScreen(
    status: ReceiverStatus,
    problems: List<String>,
    values: SettingsValues,
    actions: SettingsActions,
    version: String,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Row(
        modifier
            .fillMaxSize()
            .background(CrosscastColors.Background)
            .padding(horizontal = 56.dp, vertical = 40.dp)
    ) {
        StatusPanel(status, problems, values.friendlyName, version, zone, Modifier.width(380.dp).fillMaxHeight())
        Spacer(Modifier.width(40.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionTitle("Settings") }
            item { NameItem(values.friendlyName, actions::setFriendlyName) }
            item {
                ToggleItem("Show Now Playing when music starts", "Wakes the TV and brings the player forward", values.autoShowUi, actions::setAutoShowUi)
            }
            item {
                val next = IDLE_TIMEOUT_CHOICES.firstOrNull { it > values.idleTimeoutS } ?: IDLE_TIMEOUT_CHOICES.first()
                ListItem(
                    selected = false,
                    onClick = { actions.setIdleTimeoutS(next) },
                    headlineContent = { Text("Return to the previous screen after") },
                    supportingContent = { Text("How long the clock stays once music stops") },
                    trailingContent = { Value(duration(values.idleTimeoutS)) },
                )
            }
            item { LatencyItem(values.latencyOffsetMs, actions::setLatencyOffsetMs) }
            item {
                ToggleItem("Start when the TV turns on", "Keeps Room Speaker available after a restart", values.startOnBoot, actions::setStartOnBoot)
            }
        }
    }
}

@Composable
private fun StatusPanel(status: ReceiverStatus, problems: List<String>, name: String, version: String, zone: ZoneId, modifier: Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
            .padding(28.dp)
    ) {
        Text(name, color = CrosscastColors.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ok = status.running && status.registered
            Box(Modifier.size(10.dp).background(if (ok) Color(0xFF30D158) else Color(0xFFFF453A), CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    !status.running -> "Receiver stopped"
                    !status.registered -> "Running, not visible yet"
                    else -> "Ready for AirPlay"
                },
                color = CrosscastColors.TextSecondary,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
        Fact("Address", status.address?.let { "$it:${status.port ?: "–"}" } ?: "No network")
        Fact("Bonjour name", status.bonjourName ?: "–")
        Fact(
            "Last session",
            status.lastSender?.let { sender ->
                val at = status.lastSessionAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm")) }
                listOfNotNull(sender, at).joinToString(" · ")
            } ?: "None yet",
        )
        Spacer(Modifier.height(16.dp))
        Text("Recent problems", color = CrosscastColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        if (problems.isEmpty()) {
            Text("None", color = CrosscastColors.TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp)
        } else {
            problems.take(5).forEach {
                Text(it, color = CrosscastColors.TextSecondary.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Crosscast $version", color = CrosscastColors.TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Text(label, color = CrosscastColors.TextSecondary, fontSize = 13.sp)
    Text(value, color = CrosscastColors.TextPrimary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = CrosscastColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
}

@Composable
private fun Value(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ToggleItem(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                // iOS switches: system green when on, graphite when off, always a white thumb.
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF30D158),
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF39393D),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

@Composable
private fun NameItem(name: String, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        ListItem(
            selected = false,
            onClick = { editing = true },
            headlineContent = { Text("Name") },
            supportingContent = { Text("Shown in the AirPlay list on iPhone and Mac") },
            trailingContent = { Value(name) },
        )
        return
    }
    var text by remember { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(40) },
        singleLine = true,
        textStyle = TextStyle(color = CrosscastColors.TextPrimary, fontSize = 20.sp),
        cursorBrush = SolidColor(CrosscastColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onSave(text)
            editing = false
        }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .border(2.dp, CrosscastColors.Accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun LatencyItem(offsetMs: Int, onChange: (Int) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onChange(0) },
        headlineContent = { Text("Audio delay adjustment") },
        supportingContent = { Text("◀ ▶ to adjust in 10 ms steps · OK to reset") },
        trailingContent = { Value((if (offsetMs > 0) "+" else "") + "$offsetMs ms") },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onChange((offsetMs - 10).coerceAtLeast(-500)); true }
                Key.DirectionRight -> { onChange((offsetMs + 10).coerceAtMost(500)); true }
                else -> false
            }
        },
    )
}

private fun duration(seconds: Int): String = when {
    seconds < 60 -> "$seconds seconds"
    seconds == 60 -> "1 minute"
    else -> "${seconds / 60} minutes"
}
