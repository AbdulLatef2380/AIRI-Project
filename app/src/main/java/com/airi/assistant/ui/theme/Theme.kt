package com.airi.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Dark colour scheme ────────────────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary          = CosmicAccent,
    secondary        = CosmicAccentAlt,
    tertiary         = SemanticSuccess,
    background       = CosmicBlack,
    surface          = SurfaceBase,
    surfaceVariant   = SurfaceRaised,
    onPrimary        = Color.White,
    onSecondary      = Color.White,
    onBackground     = Color.White,
    onSurface        = Color.White,
    onSurfaceVariant = Color.White.copy(alpha = 0.65f),
    outline          = GlassWhiteBorder,
    error            = SemanticError,
    onError          = Color.White,
)

// ── Light colour scheme ───────────────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary          = CosmicAccent,
    secondary        = CosmicAccentAlt,
    tertiary         = SemanticSuccess,
    background       = LightBackground,
    surface          = LightSurface,
    surfaceVariant   = LightSurfaceRaised,
    onPrimary        = Color.Black,
    onSecondary      = Color.Black,
    onBackground     = LightOnBackground,
    onSurface        = LightOnSurface,
    onSurfaceVariant = LightOnSurface.copy(alpha = 0.65f),
    outline          = LightOutline,
    error            = SemanticError,
    onError          = Color.Black,
)

// ── Typography ────────────────────────────────────────────────────────────────
private val AIRITypography = Typography(
    displaySmall   = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

// ── AMOLED colour scheme — pure black for OLED power saving () ───────────
private val AmoledColorScheme = darkColorScheme(
    primary          = CosmicAccent,
    secondary        = CosmicAccentAlt,
    tertiary         = SemanticSuccess,
    background       = Color.Black,          // pure black — saves OLED power
    surface          = Color(0xFF0A0A0A),    // near-black surface
    surfaceVariant   = Color(0xFF111111),    // slightly raised
    onPrimary        = Color.White,
    onSecondary      = Color.White,
    onBackground     = Color.White,
    onSurface        = Color.White,
    onSurfaceVariant = Color.White.copy(alpha = 0.65f),
    outline          = Color.White.copy(alpha = 0.12f),
    error            = SemanticError,
    onError          = Color.White,
)

/**
 * Root theme composable.
 */
@Composable
fun AiriTheme(content: @Composable () -> Unit) {
    val context          = LocalContext.current
    val themePrefs       = ThemePreferences.get(context)
    val themeMode        by themePrefs.themeMode.collectAsState()
    val systemIsDark     = isSystemInDarkTheme()

    val colorScheme = when (themeMode) {
        ThemeMode.DARK   -> DarkColorScheme
        ThemeMode.LIGHT  -> LightColorScheme
        ThemeMode.SYSTEM -> if (systemIsDark) DarkColorScheme else LightColorScheme
        ThemeMode.AMOLED -> AmoledColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = AIRITypography,
        content     = content,
    )
}

/**
 * Static object to access theme tokens.
 */
object AiriTheme {
    val colorScheme: ColorScheme
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography

    // Helper accessors for common tokens
    val background: Color @Composable @ReadOnlyComposable get() = colorScheme.background
    val surface: Color @Composable @ReadOnlyComposable get() = colorScheme.surface
    val surfaceVariant: Color @Composable @ReadOnlyComposable get() = colorScheme.surfaceVariant
    val onBackground: Color @Composable @ReadOnlyComposable get() = colorScheme.onBackground
    val onSurface: Color @Composable @ReadOnlyComposable get() = colorScheme.onSurface
    val onSurfaceVariant: Color @Composable @ReadOnlyComposable get() = colorScheme.onSurfaceVariant
    val primary: Color @Composable @ReadOnlyComposable get() = colorScheme.primary
    val outline: Color @Composable @ReadOnlyComposable get() = colorScheme.outline
    val error: Color @Composable @ReadOnlyComposable get() = colorScheme.error
}
