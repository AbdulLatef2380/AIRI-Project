package com.airi.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand palette ────────────────────────────────────────────────────────────
val CosmicBlack      = Color(0xFF070C1A)
val CosmicDarkBlue   = Color(0xFF0F1529)
val CosmicAccent     = Color(0xFF00C8E0)
val CosmicAccentDark = Color(0xFF008FA8)

// ── Surface elevation ladder ─────────────────────────────────────────────────
val SurfaceBase      = Color(0xFF0C1022)
val SurfaceRaised    = Color(0xFF141C30)
val SurfaceFloating  = Color(0xFF1C2440)
val SurfaceHighlight = Color(0xFF232D4A)

// ── Semantic ─────────────────────────────────────────────────────────────────
val SemanticSuccess  = Color(0xFF32D74B)
val SemanticError    = Color(0xFFFF453A)
val SemanticWarn     = Color(0xFFFFD60A)

// ── Glass surfaces ────────────────────────────────────────────────────────────
val GlassWhite       = Color.White.copy(alpha = 0.07f)
val GlassWhiteBorder = Color.White.copy(alpha = 0.10f)

// ── Chat bubbles ──────────────────────────────────────────────────────────────
val UserBubbleSurface = Color(0xFF162C45)
val UserBubbleBorder  = Color(0xFF00C8E0).copy(alpha = 0.28f)
val AiBubbleSurface   = Color(0xFF101624)
val AiBubbleBorder    = Color.White.copy(alpha = 0.07f)

// ── Compatibility aliases ────────────────────────────────────────────────────
val MessageBubbleUser  = UserBubbleSurface
val MessageBubbleAI    = AiBubbleSurface
val InputBarBackground = Color.Black.copy(alpha = 0.60f)
val OverlayBackground  = Color.Black.copy(alpha = 0.70f)
val DividerColor       = Color.White.copy(alpha = 0.06f)
