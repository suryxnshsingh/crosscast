package dev.suryansh.airspeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.suryansh.airspeaker.R

/** Placeholder shown while no AirPlay session is active. */
@Composable
fun IdleScreen(friendlyName: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AirSpeakerColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(AirSpeakerColors.Accent, CircleShape)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = friendlyName,
                color = AirSpeakerColors.TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.waiting_for_airplay),
                color = AirSpeakerColors.TextSecondary,
                fontSize = 18.sp,
            )
        }
    }
}
