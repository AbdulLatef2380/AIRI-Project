package com.airi.assistant.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composable as ComposeAnnotation
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// AIRI Design System — unified tokens for all UI layers
// Single source of truth for spacing, motion, surfaces, gradients, shadows
// ─────────────────────────────────────────────────────────────────────────────

// ── Spacing scale ─────────────────────────────────────────────────────────────
object AIRISpacing {
    val xs  : Dp = 4.dp
    val sm  : Dp = 8.dp
    val md  : Dp = 12.dp
    val lg  : Dp = 16.dp
    val xl  : Dp = 20.dp
    val xxl : Dp = 24.dp
    val xxxl: Dp = 32.dp
    val huge: Dp = 48.dp

    // Component-specific
    val inputBarPadding  : Dp = 12.dp
    val cardPadding      : Dp = 16.dp
    val screenPadding    : Dp = 16.dp
    val panelPadding     : Dp = 14.dp
    val messagePadding   : Dp = 14.dp
    val bubbleHorizPad   : Dp = 14.dp
    val bubbleVertPad    : Dp = 10.dp
    val bottomNavHeight  : Dp = 62.dp
    val topBarHeight     : Dp = 56.dp
}

// ── Corner radius system ──────────────────────────────────────────────────────
object AIRIShapes {
    val xs     = RoundedCornerShape(6.dp)
    val sm     = RoundedCornerShape(10.dp)
    val md     = RoundedCornerShape(14.dp)
    val lg     = RoundedCornerShape(18.dp)
    val xl     = RoundedCornerShape(22.dp)
    val pill   = RoundedCornerShape(50)
    // Chat bubbles
    val userBubble = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    val aiBubble   = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    val sheet      = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

// ── Motion system ─────────────────────────────────────────────────────────────
object AIRIAnimations {
    // Durations (ms)
    const val FAST    = 160
    const val NORMAL  = 280
    const val SLOW    = 400
    const val SLOWER  = 600
    const val BREATHE = 2_000

    // Easing curves
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val standardEasing       = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val springy              = FastOutSlowInEasing

    // Preset tweens
    fun fast()   = tween<Float>(FAST,   easing = emphasizedDecelerate)
    fun normal() = tween<Float>(NORMAL, easing = standardEasing)
    fun slow()   = tween<Float>(SLOW,   easing = emphasizedDecelerate)
}

// ── Surface / glass system ────────────────────────────────────────────────────
object AIRISurfaces {
    // Base surfaces
    val base      = SurfaceBase
    val raised    = SurfaceRaised
    val floating  = SurfaceFloating
    val highlight = SurfaceHighlight
    val card      = SurfaceCard

    // Glass overlays
    val glass        = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val glassBorder  = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val glassPurple  = GlassPurple
    val glassBorderP = GlassPurpleBorder

    // Semantic state surfaces
    val successSurface = SemanticSuccess.copy(alpha = 0.12f)
    val errorSurface   = SemanticError.copy(alpha = 0.12f)
    val warnSurface    = SemanticWarn.copy(alpha = 0.12f)
    val accentSurface  = CosmicAccent.copy(alpha = 0.14f)
}

// ── Gradient system ───────────────────────────────────────────────────────────
object AIRIGradients {
    // Atmospheric background glow (top-center violet)
    val atmosphericGlow = Brush.radialGradient(
        colors = listOf(
            Color(0xFF2D1B69).copy(alpha = 0.55f),
            Color(0xFF1A0F4A).copy(alpha = 0.30f),
            Color.Transparent
        )
    )

    // Agent activity pulse
    val agentPulse = Brush.radialGradient(
        colors = listOf(
            CosmicAccent.copy(alpha = 0.35f),
            CosmicAccent.copy(alpha = 0.0f)
        )
    )

    // Chat input bar gradient
    val inputBar = Brush.verticalGradient(
        colors = listOf(Color.Transparent, CosmicBlack.copy(alpha = 0.8f))
    )

    // User bubble gradient
    val userBubble = Brush.linearGradient(
        colors = listOf(CosmicAccent, CosmicAccentDark)
    )

    // Voice state glow
    val voiceListening = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00B4D8).copy(alpha = 0.4f),
            Color.Transparent
        )
    )
    val voiceSpeaking = Brush.radialGradient(
        colors = listOf(
            CosmicAccent.copy(alpha = 0.5f),
            Color.Transparent
        )
    )

    // Connector health
    fun connectorGradient(isConnected: Boolean) = Brush.linearGradient(
        colors = if (isConnected)
            listOf(SemanticSuccess.copy(alpha = 0.15f), SemanticSuccess.copy(alpha = 0.05f))
        else
            listOf(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f))
    )

    // Agent task state
    fun taskGradient(isActive: Boolean) = Brush.linearGradient(
        colors = if (isActive)
            listOf(CosmicAccent.copy(alpha = 0.18f), CosmicAccent.copy(alpha = 0.06f))
        else
            listOf(SurfaceRaised, SurfaceCard)
    )

    // Settings row
    val settingsRow = Brush.linearGradient(
        colors = listOf(SurfaceFloating, SurfaceCard)
    )
}

// ── Semantic color helpers ────────────────────────────────────────────────────
object AIRIColors {
    // Agent state colors
    val agentPlanning   = CosmicAccent
    val agentExecuting  = Color(0xFF4FC3F7)
    val agentReflecting = Color(0xFFB57BFF)
    val agentCompleted  = SemanticSuccess
    val agentFailed     = SemanticError
    val agentRecovering = SemanticWarn
    val agentIdle       = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    // Connector state colors
    val connectorOnline  = SemanticSuccess
    val connectorOffline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val connectorError   = SemanticError
    val connectorPending = SemanticWarn

    // Voice state colors
    val voiceIdle       = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val voiceListening  = Color(0xFF00B4D8)
    val voiceSpeaking   = CosmicAccent
    val voiceProcessing = SemanticWarn
    val voiceError      = SemanticError

    // Task state colors
    val taskQueued    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val taskRunning   = CosmicAccent
    val taskDone      = SemanticSuccess
    val taskFailed    = SemanticError
    val taskRetrying  = SemanticWarn
    val taskCancelled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
}

// ── Elevation / shadow tokens (expressed as alpha on surface layers) ──────────
object AIRIShadows {
    // Simulate elevation with background tinting
    val elevation0 = SurfaceBase
    val elevation1 = SurfaceRaised
    val elevation2 = SurfaceFloating
    val elevation3 = SurfaceHighlight
    val elevation4 = SurfaceCard

    // Glow radii (dp)
    val glowSmall  : Dp = 12.dp
    val glowMedium : Dp = 24.dp
    val glowLarge  : Dp = 48.dp

    // Glow colors
    val accentGlow  = CosmicAccent.copy(alpha = 0.25f)
    val successGlow = SemanticSuccess.copy(alpha = 0.22f)
    val errorGlow   = SemanticError.copy(alpha = 0.22f)
}

// ── : Theme-aware surface helpers ─────────────────────────────────────────
// These @Composable properties read from MaterialTheme.colorScheme and
// therefore respond to Dark / Light / AMOLED mode switches.
// Screens that adopt these instead of hardcoded CosmicBlack/SurfaceCard will
// automatically support all three themes.
//
// Migration path (incremental — no big-bang rewrite required):
//   Old: color = CosmicBlack
//   New: color = AiriTheme.background
//
//   Old: color = SurfaceCard
//   New: color = AiriTheme.surface
//
//   Old: color = MaterialTheme.colorScheme.onSurface
//   New: color = AiriTheme.onBackground
//
//   Old: color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
//   New: color = AiriTheme.onSurfaceVariant

object AiriTheme {
    val background: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.background
    val surface: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.surface
    val surfaceVariant: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.surfaceVariant
    val onBackground: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.onBackground
    val onSurface: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.onSurfaceVariant
    val primary: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.primary
    val outline: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.outline
    val error: Color @ComposeAnnotation get() = MaterialTheme.colorScheme.error
}
