package com.airi.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * Animated star background with purple atmospheric glow.
 * Matches the reference design: deep dark with top-center violet radial glow.
 */

private data class Star(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val alpha: Float
)

@Composable
fun StarBackground() {
    val stars = remember {
        List(90) {
            Star(
                x     = Random.nextFloat(),
                y     = Random.nextFloat(),
                vx    = (Random.nextFloat() - 0.5f) * 0.0008f,
                vy    = (Random.nextFloat() - 0.5f) * 0.0008f,
                size  = Random.nextFloat() * 1.6f + 0.5f,
                alpha = Random.nextFloat() * 0.55f + 0.15f
            )
        }
    }

    var frameTime by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis { frameTime = it }
            stars.forEach { star ->
                star.x += star.vx
                star.y += star.vy
                if (star.x > 1f) star.x = 0f
                if (star.x < 0f) star.x = 1f
                if (star.y > 1f) star.y = 0f
                if (star.y < 0f) star.y = 1f
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        @Suppress("UNUSED_VARIABLE")
        val interaction = frameTime

        // ── Base background: deep dark ─────────────────────────────────────
        drawRect(color = Color(0xFF080B14))

        // ── Atmospheric purple radial glow (top-center) ───────────────────
        // This creates the signature purple mist seen in the reference designs
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF2D1B69).copy(alpha = 0.55f),
                    Color(0xFF1A0F4A).copy(alpha = 0.30f),
                    Color.Transparent
                ),
                center = Offset(w * 0.5f, 0f),
                radius = w * 0.85f
            )
        )

        // ── Subtle bottom vignette ────────────────────────────────────────
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF050810).copy(alpha = 0.65f)
                ),
                startY = h * 0.55f,
                endY   = h
            )
        )

        // ── Stars ─────────────────────────────────────────────────────────
        stars.forEach { star ->
            drawCircle(
                color  = Color.White.copy(alpha = star.alpha),
                radius = star.size,
                center = Offset(star.x * w, star.y * h)
            )
        }
    }
}
