package com.airi.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import com.airi.assistant.ui.theme.CosmicAccent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * Three-dot breathing indicator shown while the model is generating a response.
 * Replaces the previous "Thinking..." text label.
 *
 * Colors come from CosmicAccent so they adapt correctly
 * to Dark, Light, and AMOLED themes without any hardcoded values.
 * Dots are staggered by 160 ms to create a left-to-right wave feel.
 */
@Composable
fun ThinkingAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")

    Row(
        modifier              = modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val scale by transition.animateFloat(
                initialValue  = 0.50f,
                targetValue   = 1.00f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(
                        durationMillis = 480,
                        delayMillis    = i * 160,
                        easing         = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale_$i"
            )
            val alpha by transition.animateFloat(
                initialValue  = 0.28f,
                targetValue   = 0.88f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(
                        durationMillis = 480,
                        delayMillis    = i * 160,
                        easing         = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha_$i"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(CosmicAccent.copy(alpha = alpha))
            )
        }
    }
}
