package com.airi.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * Animated star background - Floating Particle System
 * يحاكي حركة النجوم العشوائية والبطيئة في الفضاء
 */

private data class Star(
    var x: Float,
    var y: Float,
    val vx: Float, // السرعة الأفقي
    val vy: Float, // السرعة الرأسية
    val size: Float,
    val alpha: Float
)

@Composable
fun StarBackground() {
    // إنشاء النجوم مرة واحدة وتذكرها
    val stars = remember {
        List(100) {
            Star(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                vx = (Random.nextFloat() - 0.5f) * 0.001f, // حركة بطيئة جداً يميناً أو يساراً
                vy = (Random.nextFloat() - 0.5f) * 0.001f, // حركة بطيئة جداً فوق أو تحت
                size = Random.nextFloat() * 2f + 1f,
                alpha = Random.nextFloat() * 0.7f + 0.3f
            )
        }
    }

    // محرك الحركة (Infinite Frame Loop)
    var frameTime by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis { frameTime = it }
            stars.forEach { star ->
                // تحديث المواقع
                star.x += star.vx
                star.y += star.vy

                // التفاف النجوم (Wrap around) لضمان عدم خروجها نهائياً
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

        // قمنا بربط الرسم بـ frameTime ليتم تحديثه في كل إطار
        val interaction = frameTime 

        stars.forEach { star ->
            drawCircle(
                color = Color.White.copy(alpha = star.alpha),
                radius = star.size,
                center = Offset(star.x * w, star.y * h)
            )
        }
    }
}
