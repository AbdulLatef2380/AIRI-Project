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
import com.airi.assistant.ui.theme.AiriTheme
import kotlin.random.Random

/**
 * Subtle star background with a neutral graphite atmosphere.
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

    // Read theme colours in the composable scope; Canvas draw lambdas are not
    // composable and must only receive already-resolved values.
    val background = AiriTheme.background
    val foreground = AiriTheme.onBackground

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        @Suppress("UNUSED_VARIABLE")
        val interaction = frameTime

        // ── Base background: deep dark ─────────────────────────────────────
        drawRect(color = background)

        // ── Neutral graphite radial light (top-center) ─────────────────────
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    foreground.copy(alpha = 0.10f),
                    foreground.copy(alpha = 0.06f),
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
                    foreground.copy(alpha = 0.12f)
                ),
                startY = h * 0.55f,
                endY   = h
            )
        )

        // ── Stars ─────────────────────────────────────────────────────────
        stars.forEach { star ->
            drawCircle(
                color  = foreground.copy(alpha = star.alpha * 0.55f),
                radius = star.size,
                center = Offset(star.x * w, star.y * h)
            )
        }
    }
}
