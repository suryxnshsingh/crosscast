package dev.suryansh.crosscast.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import dev.suryansh.crosscast.R

enum class SetupStep { Allow, Done, NoSettingsScreen }

/** The first-run screen: one permission, one button, centred on the moving background. */
@Composable
fun SetupScreen(
    step: SetupStep,
    friendlyName: String,
    adbCommand: String,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    motion: Boolean = true,
) {
    Box(modifier.fillMaxSize().background(CrosscastColors.Background), contentAlignment = Alignment.Center) {
        FluidBackground(ArtworkStyle.Default, motion, Modifier.fillMaxSize())
        Column(Modifier.width(600.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LogoTile()
            Spacer(Modifier.height(28.dp))
            when (step) {
                SetupStep.Allow -> {
                    Heading("Let Crosscast wake your TV")
                    Body("When music starts, Crosscast wakes the TV and shows Now Playing. To do that, it needs permission to display over other apps.")
                    Buttons(step) { primary ->
                        Pill("Open TV Settings", primary, onOpenSettings)
                        Pill("Not Now", Modifier, onDone)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "In the list, move to Crosscast and press OK to turn it on. Then press Back.",
                        color = CrosscastColors.TextSecondary,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                SetupStep.Done -> {
                    Heading("You’re all set")
                    Body("On your iPhone, open Control Center, tap AirPlay and choose $friendlyName.")
                    Buttons(step) { primary -> Pill("Done", primary, onDone) }
                }
                SetupStep.NoSettingsScreen -> {
                    Heading("One more step")
                    Body("This TV has no settings screen for the permission Crosscast needs. From a computer connected to the TV with adb, run:")
                    Spacer(Modifier.height(20.dp))
                    Text(
                        adbCommand,
                        color = CrosscastColors.TextPrimary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                    Buttons(step) { primary -> Pill("Done", primary, onDone) }
                }
            }
        }
    }
}

/** The launcher icon, drawn the same way: the mark on Crosscast's gradient, with continuous corners. */
@Composable
private fun LogoTile() {
    val shape = remember { ContinuousRoundedShape(20.dp) }
    Box(
        Modifier
            .size(92.dp)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF6A4CF5), Color(0xFFB24ACB), Color(0xFFFF7A59)),
                    start = Offset(0f, Float.POSITIVE_INFINITY),
                    end = Offset(Float.POSITIVE_INFINITY, 0f),
                ),
                shape,
            ),
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.fillMaxSize())
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        color = CrosscastColors.TextPrimary,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        style = TextStyle(fontFamily = InterDisplay, letterSpacing = (-0.02).em),
    )
}

@Composable
private fun Body(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, color = CrosscastColors.TextSecondary, fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center)
}

/** A row of buttons, with focus moved to [content]'s primary button whenever the step changes. */
@Composable
private fun Buttons(step: SetupStep, content: @Composable (primary: Modifier) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(step) { focus.requestFocus() }
    Spacer(Modifier.height(32.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { content(Modifier.focusRequester(focus)) }
}

/** tvOS-style button: translucent at rest, white when focused. */
@Composable
private fun Pill(text: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = CrosscastColors.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
    }
}
