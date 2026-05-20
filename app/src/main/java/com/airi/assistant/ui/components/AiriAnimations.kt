package com.airi.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SurfaceRaised

// ─────────────────────────────────────────────────────────────────────────────
// AIRI Microinteraction components (Phase 10)
// All are pure-Compose, recomposition-safe, state-driven.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ShimmerBox — animated shimmer skeleton for loading states.
 *
 * Usage:
 * ```
 * ShimmerBox(modifier = Modifier.fillMaxWidth().height(48.dp))
 * ```
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    baseColor: Color   = SurfaceRaised,
    shimmerColor: Color = Color.White.copy(alpha = 0.06f),
    cornerRadius: Dp   = 10.dp
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1000f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors      = listOf(baseColor, shimmerColor, baseColor),
                    start       = Offset(shimmerTranslate - 400f, 0f),
                    end         = Offset(shimmerTranslate + 400f, 0f)
                )
            )
    )
}

/**
 * Skeleton loading layout for chat message while model is loading.
 */
@Composable
fun MessageSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.75f).height(14.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.55f).height(14.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.40f).height(14.dp))
    }
}

/**
 * TypingIndicator — three-dot animated typing indicator for AI response.
 */
@Composable
fun TypingIndicator(
    color:    Color = CosmicAccent,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "typing")

    val dot1Alpha by infinite.animateFloat(initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing, delayMillis = 0), RepeatMode.Reverse), label = "d1")
    val dot2Alpha by infinite.animateFloat(initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing, delayMillis = 150), RepeatMode.Reverse), label = "d2")
    val dot3Alpha by infinite.animateFloat(initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing, delayMillis = 300), RepeatMode.Reverse), label = "d3")

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

/**
 * PulsingDot — status indicator that breathes when active.
 */
@Composable
fun PulsingDot(
    active:   Boolean,
    color:    Color   = CosmicAccent,
    size:     Dp      = 8.dp,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "dot")
    val alpha by if (active) {
        infinite.animateFloat(
            initialValue  = 0.4f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label         = "dotAlpha"
        )
    } else {
        remember { mutableStateOf(0.35f) }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * StreamingCursor — blinking cursor shown at the end of streaming text.
 */
@Composable
fun StreamingCursor(
    visible:  Boolean,
    color:    Color   = CosmicAccent,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "cursor")
    val alpha by infinite.animateFloat(
        initialValue  = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse),
        label         = "cursorAlpha"
    )
    if (visible) {
        Box(
            modifier = modifier
                .width(2.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color.copy(alpha = alpha))
        )
    }
}

/**
 * AgentThinkingIndicator — animated "thinking" state shown below the input
 * bar while the agent plans / infers.
 */
@Composable
fun AgentThinkingIndicator(
    visible:  Boolean,
    label:    String  = "AIRI يفكر…",
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter   = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit    = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
        modifier = modifier
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            TypingIndicator(color = CosmicAccent)
            androidx.compose.material3.Text(
                text     = label,
                fontSize = 12.sp,
                color    = CosmicAccent.copy(alpha = 0.75f)
            )
        }
    }
}
