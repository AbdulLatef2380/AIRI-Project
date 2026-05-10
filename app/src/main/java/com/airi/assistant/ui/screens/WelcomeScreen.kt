package com.airi.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.NeuralAccentButton
import com.airi.assistant.ui.theme.*

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0),
        contentAlignment = Alignment.Center
    ) {
        // Background glow
        Box(
            modifier = Modifier
                .size(480.dp)
                .background(Brush.radialGradient(listOf(PrimaryAccent.copy(0.10f), Color.Transparent)))
                .align(Alignment.Center)
        )

        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val inf = rememberInfiniteTransition(label = "welcome_pulse")
            val alpha by inf.animateFloat(0.18f, 0.35f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "a")

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(PrimaryAccent.copy(alpha), Color.Transparent)))
                    .border(2.dp, PrimaryAccent.copy(0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = PrimaryAccent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            Text("AIRI", color = TextPrimary, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text("مرحباً بك في مستقبل الذكاء الاصطناعي", color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
            Spacer(Modifier.height(8.dp))
            NeuralAccentButton("ابدأ الآن", onClick = onStart, modifier = Modifier.width(240.dp))
        }
    }
}
