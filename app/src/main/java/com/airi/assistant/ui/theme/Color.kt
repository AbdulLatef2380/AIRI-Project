package com.airi.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ── Core Surface Scale ────────────────────────────────────────────────────────
val Surface0         = Color(0xFF08080F)
val Surface1         = Color(0xFF111120)
val Surface2         = Color(0xFF191928)
val Surface3         = Color(0xFF222238)
val SurfaceRaised    = Surface2
val SurfaceFloating  = Surface3

// ── Accent System ─────────────────────────────────────────────────────────────
val PrimaryAccent    = Color(0xFF7C5FFF)
val AccentDark       = Color(0xFF6347E8)
val SecondaryAccent  = Color(0xFF4FC3F7)
val SemanticSuccess  = Color(0xFF00DFA2)
val SemanticWarning  = Color(0xFFFFB830)
val SemanticError    = Color(0xFFFF4D6D)

// ── Execution Badge Colors ────────────────────────────────────────────────────
val AccentLocal      = Color(0xFFA78BFA)
val AccentCloud      = Color(0xFF60A5FA)
val AccentHybrid     = Color(0xFF34D399)

// ── Text Scale ────────────────────────────────────────────────────────────────
val TextPrimary      = Color(0xFFEEEEFF)
val TextSecondary    = Color(0xFF8888AA)
val TextTertiary     = Color(0xFF55556F)

// ── Border Scale ──────────────────────────────────────────────────────────────
val BorderLight      = Color(0xFF2D2D4E)
val BorderMid        = Color(0xFF3D3D6A)
val BorderDark       = Color(0xFF1D1D30)

// ── Message Bubble Tokens ─────────────────────────────────────────────────────
val UserBubbleSurface  = PrimaryAccent
val AiBubbleSurface    = Surface2
val AiBubbleBorder     = BorderLight
val MessageBubbleUser  = PrimaryAccent
val MessageBubbleAI    = Surface2
val InputBarBackground = Surface3
val OverlayBackground  = Color(0xFF08080F).copy(alpha = 0.82f)
val DividerColor       = BorderDark

// ── Glass / Frosted surface tokens ────────────────────────────────────────────
val GlassWhite       = Surface1.copy(alpha = 0.85f)
val GlassWhiteBorder = BorderLight

// ── Glow / Ambient light tokens ───────────────────────────────────────────────
val AccentGlow      = PrimaryAccent.copy(alpha = 0.25f)
val AccentGlowFaint = PrimaryAccent.copy(alpha = 0.10f)

// ── Legacy Compatibility Aliases ──────────────────────────────────────────────
val CosmicAccent   = PrimaryAccent
val CosmicBlack    = Surface0
val CosmicDarkBlue = Surface2
val SemanticWarn   = SemanticWarning

// ── Missing color aliases (used by NeuralComponents / KnowledgeScreen) ────────
val AccentBlue = SecondaryAccent
val BorderLow  = BorderLight
