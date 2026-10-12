package com.talktomyfinances.android.ui.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.talktomyfinances.android.domain.VoiceSessionStatus

@Composable
fun VoiceWaveformBar(
    status: VoiceSessionStatus,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")

    val isOscillating = !isMuted && (status == VoiceSessionStatus.LISTENING || status == VoiceSessionStatus.SPEAKING || status == VoiceSessionStatus.CONNECTED)

    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = if (isOscillating) 0.85f else 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val scale2 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (isOscillating) 1.0f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, delayMillis = 100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val scale3 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isOscillating) 0.75f else 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, delayMillis = 50, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val scale4 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (isOscillating) 0.95f else 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620, delayMillis = 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    val scale5 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = if (isOscillating) 0.7f else 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 390, delayMillis = 80, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar5"
    )

    val barColor = when {
        isMuted -> MaterialTheme.colorScheme.outline
        status == VoiceSessionStatus.SPEAKING -> MaterialTheme.colorScheme.primary
        status == VoiceSessionStatus.LISTENING -> MaterialTheme.colorScheme.tertiary
        status == VoiceSessionStatus.CONNECTING -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    }

    val scales = listOf(scale1, scale2, scale3, scale4, scale5)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scales.forEach { scale ->
            WaveformBar(heightFraction = scale, color = barColor)
        }
    }
}

@Composable
private fun WaveformBar(
    heightFraction: Float,
    color: Color,
    maxHeightDp: Int = 28
) {
    val barHeight = (maxHeightDp * heightFraction).coerceIn(4f, maxHeightDp.toFloat()).dp

    Box(
        modifier = Modifier
            .width(4.dp)
            .height(barHeight)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}
