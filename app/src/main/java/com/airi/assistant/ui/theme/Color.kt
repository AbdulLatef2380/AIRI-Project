package com.airi.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ── Core Surface Scale — matches React prototype exactly ─────────────────────
// Prototype tokens: bg=#08080f  surface=#111120  surfaceB=#191928  surfaceC=#222238
val Surface0         = Color(0xFF08080F)  // deep near-black — global background
val Surface1         = Color(0xFF111120)  // card background
val Surface2         = Color(0xFF191928)  // raised elements / elevated card
val Surface3         = Color(0xFF222238)  // modal / bottom sheet / active state
val SurfaceRaised    = Surface2           // semantic alias — raised card surface
val SurfaceFloating  = Surface3           // semantic alias — floating overlay / tooltip

// ── Accent System ─────────────────────────────────────────────────────────────
// Prototype: accent=#7c5fff  accentDark=#6347e8  secondary=#00dfa2
val PrimaryAccent    = Color(0xFF7C5FFF)  // Neural Violet — primary brand
val AccentDark       = Color(0xFF6347E8)  // darker violet — pressed / shadow
val SecondaryAccent  = Color(0xFF4FC3F7)  // soft sky blue — runtime indicators
val SemanticSuccess  = Color(0xFF00DFA2)  // teal-green — completed / connected
val SemanticWarning  = Color(0xFFFFB830)  // amber — degraded / fallback
val SemanticError    = Color(0xFFFF4D6D)  // vivid red-pink — errors / disconnected

// ── Execution Badge Colors ────────────────────────────────────────────────────
val AccentLocal      = Color(0xFFA78BFA)  // lavender — LOCAL badge
val AccentCloud      = Color(0xFF60A5FA)  // sky blue — CLOUD badge
val AccentHybrid     = Color(0xFF34D399)  // teal-green — HYBRID badge

// ── Text Scale — prototype: text=#eeeeff  textB=#8888aa  textC=#55556f ───────
val TextPrimary      = Color(0xFFEEEEFF)
val TextSecondary    = Color(0xFF8888AA)
val TextTertiary     = Color(0xFF55556F)

// ── Border Scale — prototype: border=#2d2d4e  borderB=#3d3d6a ────────────────
val BorderLight      = Color(0xFF2D2D4E)
val BorderMid        = Color(0xFF3D3D6A)
val BorderDark       = Color(0xFF1D1D30)

// ── Message Bubble Tokens ─────────────────────────────────────────────────────
// User bubble: solid accent violet; AI bubble: surface with border
val UserBubbleSurface  = PrimaryAccent          // full accent for user messages
val AiBubbleSurface    = Surface2               // raised surface for AI messages
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
val AccentGlow       = PrimaryAccent.copy(alpha = 0.25f)
val AccentGlowFaint  = PrimaryAccent.copy(alpha = 0.10f)

// ── Legacy Compatibility Aliases ──────────────────────────────────────────────
// Keeps existing Kotlin files compiling without a mass rename sweep.
val CosmicAccent     = PrimaryAccent
val CosmicBlack      = Surface0
val CosmicDarkBlue   = Surface2
val SemanticWarn     = SemanticWarning
