package com.airi.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent

/**
 * Enhanced "thinking" indicator with:
 *   1. Three-dot breathing animation (staggered left-to-right wave)
 *   2. Dynamic status text that cycles through stages
 *      ("Thinking..." → "Generating..." → "Almost there...")
 *   3. Radial glow pulse behind the dots for a premium feel
 *   4. Adapts to Dark, Light, and AMOLED themes via CosmicAccent
 */
@Composable
fun ThinkingAnimation(
    modifier: Modifier = Modifier,
    stageText: String? = null  // null = auto-cycle; non-null = use caller-provided text
) {
    val transition = rememberInfiniteTransition(label = "thinking")

    // ── Glow pulse behind the dots ────────────────────────────────────────────
    val glowScale by transition.animateFloat(
        initialValue  = 0.6f,
        targetValue   = 1.2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )
    val glowAlpha by transition.animateFloat(
        initialValue  = 0.05f,
        targetValue   = 0.15f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // ── Dynamic status text cycle ─────────────────────────────────────────────
    val statusMessages = listOf("Thinking...", "Generating...", "Almost there...")
    val statusIndex by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "status_cycle"
    )

    val fadeAlpha by transition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_fade"
    )

    val effectiveText = stageText ?: statusMessages[(statusIndex.toInt()) % 3]

    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .width(IntrinsicSize.Min)
            .drawBehind {
                // Radial glow behind the dots
                val radius = 40f * glowScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CosmicAccent.copy(alpha = glowAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, 12f),
                        radius = radius
                    )
                )
            },
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status text
        Text(
            text = effectiveText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize   = 12.sp
            ),
            color = CosmicAccent.copy(alpha = fadeAlpha),
            modifier = Modifier.alpha(fadeAlpha)
        )

        // Three-dot breathing indicator
        Row(
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
}
