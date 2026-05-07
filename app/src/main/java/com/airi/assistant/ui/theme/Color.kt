package com.airi.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ── Core Surface Scale ──────────────────────────────────────────────────────
val Surface0         = Color(0xFF0A0A0F)  // deep near-black — global background
val Surface1         = Color(0xFF111118)  // card background
val Surface2         = Color(0xFF1A1A24)  // raised elements / elevated card
val Surface3         = Color(0xFF22222E)  // modal / bottom sheet / active state
val SurfaceRaised    = Surface2           // semantic alias — raised card surface
val SurfaceFloating  = Surface3           // semantic alias — floating overlay / tooltip

// ── Accent System ───────────────────────────────────────────────────────────
val PrimaryAccent    = Color(0xFF7C6EF0)  // indigo-violet — primary brand
val SecondaryAccent  = Color(0xFF4FC3F7)  // soft sky blue — runtime indicators
val SemanticSuccess  = Color(0xFF4ADE80)  // emerald — completed / connected
val SemanticWarning  = Color(0xFFFBBF24)  // amber — degraded / fallback
val SemanticError    = Color(0xFFF87171)  // soft red — errors / disconnected

// ── Execution Badge Colors ───────────────────────────────────────────────────
val AccentLocal      = Color(0xFFA78BFA)  // lavender — LOCAL badge
val AccentCloud      = Color(0xFF60A5FA)  // sky blue — CLOUD badge
val AccentHybrid     = Color(0xFF34D399)  // teal-green — HYBRID badge

// ── Text Scale ──────────────────────────────────────────────────────────────
val TextPrimary      = Color(0xFFFFFFFF)
val TextSecondary    = Color(0xFFB0B0B0)
val TextTertiary     = Color(0xFF757575)

// ── Border Scale ────────────────────────────────────────────────────────────
val BorderLight      = Color(0xFF303038)
val BorderDark       = Color(0xFF1F1F26)

// ── Message Bubble Tokens ────────────────────────────────────────────────────
val MessageBubbleUser  = Surface2
val MessageBubbleAI    = Surface1
val InputBarBackground = Surface3
val OverlayBackground  = Surface0.copy(alpha = 0.70f)
val DividerColor       = BorderDark
val AiBubbleSurface    = Surface1
val AiBubbleBorder     = BorderLight
val UserBubbleSurface  = Color(0xFF2A2060)   // rich violet for user bubbles

// ── Glass / Frosted surface tokens (used by GlassCard.kt) ────────────────────
val GlassWhite       = Surface1.copy(alpha = 0.80f)
val GlassWhiteBorder = BorderLight

// ── Legacy Compatibility Aliases ─────────────────────────────────────────────
// These keep existing Kotlin files compiling without a mass rename sweep.
// Gradually replace call-sites with the canonical names above.
val CosmicAccent     = PrimaryAccent       // was cyan #00BCD4, now violet #7C6EF0
val CosmicBlack      = Surface0            // #0A0A0F
val CosmicDarkBlue   = Surface2            // #1A1A24
val SemanticWarn     = SemanticWarning     // alias
