package com.airi.assistant.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

/**
 * Animated star background - particle system
 * Creates a cosmic effect with moving stars
 */
@Composable
fun StarBackground() {
    val stars = remember {
        List(120) {
            Animatable(initialValue = (0..1000).random() / 1000f)
        }
    }

    LaunchedEffect(Unit) {
        stars.forEach { star ->
            launch {
                while (true) {
                    star.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 8000)
                    )
                    star.snapTo(0f)
                }
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        stars.forEach {
            val y = it.value * height
            val x = (0..width.toInt()).random().toFloat()

            drawCircle(
                color = Color.White,
                radius = 1.5f,
                center = Offset(x, y)
            )
        }
    }
}
