package com.airi.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object DesktopSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xLarge = 24.dp
    val page = 28.dp
}

object DesktopShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(18.dp)
    val userMessage = RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
    val airiMessage = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
}

object DesktopColors {
    val background = Color(0xFF08111F)
    val surface = Color(0xFF0E1A2B)
    val surfaceRaised = Color(0xFF14243A)
    val outline = Color(0xFF2C4561)
    val accent = Color(0xFF7DD3FC)
    val success = Color(0xFF5EEAD4)
    val warning = Color(0xFFFBBF24)
    val error = Color(0xFFFCA5A5)
}

val AiriDesktopColorScheme = darkColorScheme(
    primary = DesktopColors.accent,
    onPrimary = Color(0xFF062033),
    secondary = Color(0xFF67E8F9),
    background = DesktopColors.background,
    surface = DesktopColors.surface,
    surfaceVariant = DesktopColors.surfaceRaised,
    outline = DesktopColors.outline,
    error = DesktopColors.error
)
