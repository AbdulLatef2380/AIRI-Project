package com.airi.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.theme.BorderLight
import com.airi.assistant.ui.theme.GlassWhite
import com.airi.assistant.ui.theme.GlassWhiteBorder
import com.airi.assistant.ui.theme.PrimaryAccent
import com.airi.assistant.ui.theme.Surface1
import com.airi.assistant.ui.theme.Surface2

/**
 * GlassCard — Neural Violet glassmorphism container.
 *
 * Default variant: dark frosted surface with border (matches the React
 * prototype's card style).  Use [variant] to switch to the accent-glow
 * variant for highlighted / active cards.
 */
@Composable
fun GlassCard(
    modifier:  Modifier    = Modifier,
    variant:   GlassVariant = GlassVariant.DEFAULT,
    radius:    Dp           = 14.dp,
    padding:   Dp           = 16.dp,
    content:   @Composable ColumnScope.() -> Unit
) {
    val (bg, borderBrush) = when (variant) {
        GlassVariant.DEFAULT -> Pair(
            Surface2,
            Brush.linearGradient(listOf(BorderLight, BorderLight))
        )
        GlassVariant.RAISED -> Pair(
            Surface1,
            Brush.linearGradient(listOf(BorderLight, BorderLight))
        )
        GlassVariant.ACCENT_GLOW -> Pair(
            Surface2,
            Brush.linearGradient(
                listOf(PrimaryAccent.copy(alpha = 0.6f), PrimaryAccent.copy(alpha = 0.2f))
            )
        )
        GlassVariant.FROSTED -> Pair(
            GlassWhite,
            Brush.linearGradient(listOf(GlassWhiteBorder, GlassWhiteBorder))
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .border(
                width  = 1.dp,
                brush  = borderBrush,
                shape  = RoundedCornerShape(radius)
            )
            .padding(padding),
        content = content
    )
}

enum class GlassVariant { DEFAULT, RAISED, ACCENT_GLOW, FROSTED }
