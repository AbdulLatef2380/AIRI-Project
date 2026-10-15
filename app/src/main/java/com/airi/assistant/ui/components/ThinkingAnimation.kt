package com.airi.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent

/**
 * Enhanced "thinking" indicator with:
 *   1. Breathing glow behind the thinking status (without an assistant icon)
 *   2. Dynamic status text that cycles through stages
 *      ("Thinking..." → "Generating..." → "Almost there...")
 *   3. Radial glow pulse behind the icon for a premium feel
 *   4. Adapts to Dark, Light, and AMOLED themes via CosmicAccent
 */
@Composable
fun ThinkingAnimation(
    modifier: Modifier = Modifier,
    stageText: String? = null, // null = auto-cycle; non-null = use caller-provided text
    animate: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
) {
    if (!animate) {
        Text(
            text = stageText ?: "Thinking…",
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            ),
            color = CosmicAccent,
            modifier = modifier.padding(contentPadding)
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "thinking")

    // ── Subtle glow pulse behind the thinking status ──────────────────────────
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

    Row(
        modifier = modifier
            .padding(contentPadding)
            .drawBehind {
                // Radial glow remains as a subtle status animation without a glyph.
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Bidi-aware status text stands alone; no assistant icon is rendered.
        Text(
            text = effectiveText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize   = 12.sp
            ),
            color = CosmicAccent.copy(alpha = fadeAlpha),
            modifier = Modifier.alpha(fadeAlpha)
        )
    }
}
