package com.airi.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// AIRI Color System — hand-crafted palette for a premium AI assistant
// Organized by purpose, not by hue. All alpha values are explicit.
// ─────────────────────────────────────────────────────────────────────────────

// ── Brand identity ────────────────────────────────────────────────────────────
// Core violet — the AIRI signature color. Derived from a 290° hue with
// high saturation, positioned between ultraviolet and indigo for distinctiveness.
val CosmicAccent     = Color(0xFF6B5CE7)   // Primary — violet 290°
val CosmicAccentAlt  = Color(0xFF8B78F0)   // Lighter — lighter tint for gradients
val CosmicAccentDark = Color(0xFF4B3DB5)   // Darker — pressed state, deep glow
val CosmicGlow       = Color(0xFF6B5CE7)   // Equals accent — used for shadow tinting

// ── Dark background ladder ────────────────────────────────────────────────────
// Five-step depth system. Higher index = closer to user = lighter.
val CosmicBlack      = Color(0xFF080B14)   // Base layer — deepest background
val CosmicDarkBlue   = Color(0xFF0D1020)   // Nav bar, bottom anchors

val SurfaceBase      = Color(0xFF0C0F1C)   // Sheet backgrounds
val SurfaceRaised    = Color(0xFF111525)   // Cards, chips, secondary elements
val SurfaceFloating  = Color(0xFF171B2E)   // Popovers, tooltips
val SurfaceHighlight = Color(0xFF1E2438)   // Selected state, active rows
val SurfaceCard      = Color(0xFF131728)   // Card surfaces — slightly warmer

// ── Glass / translucent surfaces ─────────────────────────────────────────────
val GlassWhite       = Color.White.copy(alpha = 0.06f)
val GlassWhiteBorder = Color.White.copy(alpha = 0.08f)
val GlassPurple      = CosmicAccent.copy(alpha = 0.08f)
val GlassPurpleBorder= CosmicAccent.copy(alpha = 0.20f)

// ── Semantic feedback ─────────────────────────────────────────────────────────
val SemanticSuccess  = Color(0xFF30D158)   // iOS-derived green — readable on dark
val SemanticError    = Color(0xFFFF453A)   // iOS-derived red — vibrant on dark
val SemanticWarn     = Color(0xFFFFD60A)   // Amber — caution without aggression

val SemanticSuccessContainer = SemanticSuccess.copy(alpha = 0.12f)
val SemanticErrorContainer   = SemanticError.copy(alpha = 0.12f)
val SemanticWarnContainer    = SemanticWarn.copy(alpha = 0.12f)

// ── Chat bubbles ──────────────────────────────────────────────────────────────
// User bubble: solid accent — distinct, unambiguous ownership
val UserBubbleSurface= CosmicAccent
val UserBubbleBorder = CosmicAccentAlt.copy(alpha = 0.60f)
// AI bubble: dark surface — professional, secondary presence
val AiBubbleSurface  = SurfaceRaised
val AiBubbleBorder   = Color.White.copy(alpha = 0.06f)

// ── Compatibility aliases (referenced in existing code) ───────────────────────
val MessageBubbleUser  = UserBubbleSurface
val MessageBubbleAI    = AiBubbleSurface
val InputBarBackground = SurfaceRaised.copy(alpha = 0.96f)
val OverlayBackground  = Color.Black.copy(alpha = 0.72f)
val DividerColor       = Color.White.copy(alpha = 0.07f)

// ── Navigation ────────────────────────────────────────────────────────────────
val NavBarBackground   = CosmicBlack
val NavIconInactive    = Color.White.copy(alpha = 0.42f)
val NavIconActive      = CosmicAccent

// ── Component-specific ────────────────────────────────────────────────────────
val TokenBadgeColor    = CosmicAccent.copy(alpha = 0.90f)
val ModelPillBg        = Color(0xFF1A1E34)
val ModelPillBorder    = Color.White.copy(alpha = 0.10f)

// ── Light-mode palette ───────────────────────────────────────────────────────
// Explicit high-contrast tokens. Light mode must not inherit translucent
// white-on-dark colors from the dark palette.
val LightBackground     = Color(0xFFF6F7FB)
val LightSurface        = Color(0xFFFFFFFF)
val LightSurfaceRaised  = Color(0xFFEDEFF5)
val LightSurfaceCard    = Color(0xFFFFFFFF)
val LightOnBackground   = Color(0xFF171923)
val LightOnSurface      = Color(0xFF171923)
val LightOnSurfaceMuted = Color(0xFF454858)
val LightPrimary        = Color(0xFF5143C7)
val LightSecondary      = Color(0xFF5A4BCB)
val LightOutline        = Color(0xFF303342).copy(alpha = 0.28f)
val LightDivider        = Color(0xFF303342).copy(alpha = 0.18f)
