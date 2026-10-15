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
    val barColor = when {
        isMuted -> MaterialTheme.colorScheme.outline
        status == VoiceSessionStatus.SPEAKING -> MaterialTheme.colorScheme.primary
        status == VoiceSessionStatus.LISTENING -> MaterialTheme.colorScheme.tertiary
        status == VoiceSessionStatus.CONNECTING -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    }

    val barConfigs = listOf(
        Triple(0.25f, 0.85f, 400 to 0),
        Triple(0.40f, 1.00f, 550 to 100),
        Triple(0.30f, 0.75f, 480 to 50),
        Triple(0.50f, 0.95f, 620 to 150),
        Triple(0.20f, 0.70f, 390 to 80)
    )

    val scales = barConfigs.mapIndexed { index, (minH, maxH, timing) ->
        val (duration, delay) = timing
        val scale by infiniteTransition.animateFloat(
            initialValue = minH,
            targetValue = if (isOscillating) maxH else minH,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, delayMillis = delay, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "waveform_bar_$index"
        )
        scale
    }

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
