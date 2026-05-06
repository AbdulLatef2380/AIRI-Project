package com.airi.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// New Design System Colors
val Surface0         = Color(0xFF0A0A0F) // deep near-black
val Surface1         = Color(0xFF111118) // card background
val Surface2         = Color(0xFF1A1A24) // raised elements
val Surface3         = Color(0xFF22222E) // active/selected elements
val PrimaryAccent    = Color(0xFF7C6EF0) // vibrant purple
val SecondaryAccent  = Color(0xFF4FC3F7) // light blue
val SemanticSuccess  = Color(0xFF4ADE80) // green
val SemanticWarning  = Color(0xFFFBBF24) // amber
val SemanticError    = Color(0xFFF87171) // red
val TextPrimary      = Color(0xFFFFFFFF)
val TextSecondary    = Color(0xFFB0B0B0)
val TextTertiary     = Color(0xFF757575)
val BorderLight      = Color(0xFF303038)
val BorderDark       = Color(0xFF1F1F26)

// Compatibility aliases (if needed, can be removed later)
val MessageBubbleUser  = Surface2
val MessageBubbleAI    = Surface1
val InputBarBackground = Surface3
val OverlayBackground  = Surface0.copy(alpha = 0.70f)
val DividerColor       = BorderDark
