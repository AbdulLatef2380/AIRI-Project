package com.airi.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.theme.CosmicAccent

/**
 * AP-C07: 3-dot staggered bounce animation shown between message send and first streaming token.
 * Replaces the frozen-UI gap (2–15 s for local LLM inference) with a clear "thinking" signal.
 */
@Composable
fun ThinkingAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        (0..2).forEach { i ->
            val offsetY by transition.animateFloat(
                initialValue  = 0f,
                targetValue   = -7f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(durationMillis = 380, delayMillis = i * 127, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$i"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .offset(y = offsetY.dp)
                    .clip(CircleShape)
                    .background(CosmicAccent.copy(alpha = 0.75f))
            )
        }
    }
}
