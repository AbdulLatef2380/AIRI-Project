package com.airi.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand palette — Purple/Violet (matches reference UI) ─────────────────────
val CosmicBlack      = Color(0xFF080B14)
val CosmicDarkBlue   = Color(0xFF0D1020)
val CosmicAccent     = Color(0xFF6B5CE7)   // Primary violet
val CosmicAccentAlt  = Color(0xFF7C6FF0)   // Lighter violet
val CosmicAccentDark = Color(0xFF4B3DB5)   // Darker violet
val CosmicGlow       = Color(0xFF6B5CE7)   // Glow color = accent

// ── Surface elevation ladder ─────────────────────────────────────────────────
val SurfaceBase      = Color(0xFF0C0F1C)
val SurfaceRaised    = Color(0xFF111525)
val SurfaceFloating  = Color(0xFF171B2E)
val SurfaceHighlight = Color(0xFF1E2438)
val SurfaceCard      = Color(0xFF131728)

// ── Semantic ─────────────────────────────────────────────────────────────────
val SemanticSuccess  = Color(0xFF32D74B)
val SemanticError    = Color(0xFFFF453A)
val SemanticWarn     = Color(0xFFFFD60A)

// ── Glass surfaces ────────────────────────────────────────────────────────────
val GlassWhite       = Color.White.copy(alpha = 0.06f)
val GlassWhiteBorder = Color.White.copy(alpha = 0.09f)
val GlassPurple      = CosmicAccent.copy(alpha = 0.08f)
val GlassPurpleBorder = CosmicAccent.copy(alpha = 0.20f)

// ── Chat bubbles ──────────────────────────────────────────────────────────────
val UserBubbleSurface = Color(0xFF6B5CE7)            // solid purple for user
val UserBubbleBorder  = Color(0xFF7C6FF0).copy(alpha = 0.60f)
val AiBubbleSurface   = Color(0xFF111525)
val AiBubbleBorder    = Color.White.copy(alpha = 0.07f)

// ── Compatibility aliases ────────────────────────────────────────────────────
val MessageBubbleUser  = UserBubbleSurface
val MessageBubbleAI    = AiBubbleSurface
val InputBarBackground = Color(0xFF111525).copy(alpha = 0.95f)
val OverlayBackground  = Color.Black.copy(alpha = 0.70f)
val DividerColor       = Color.White.copy(alpha = 0.06f)

// ── Bottom nav ───────────────────────────────────────────────────────────────
val NavBarBackground   = Color(0xFF0D1020)
val NavIconInactive    = Color.White.copy(alpha = 0.45f)
val NavIconActive      = CosmicAccent

// ── Top bar token badge ──────────────────────────────────────────────────────
val TokenBadgeColor    = CosmicAccent.copy(alpha = 0.85f)
val ModelPillBg        = Color(0xFF1C2038)
val ModelPillBorder    = Color.White.copy(alpha = 0.12f)
