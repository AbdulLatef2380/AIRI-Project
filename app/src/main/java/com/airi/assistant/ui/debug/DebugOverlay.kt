package com.airi.assistant.ui.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.domain.verification.VerificationEvent
import com.airi.assistant.domain.verification.VerificationTracker

private val GREEN  = Color(0xFF4CAF50)
private val BLUE   = Color(0xFF42A5F5)
private val RED    = Color(0xFFFF4444)
private val MONO   = FontFamily.Monospace

@Composable
fun DebugOverlay() {
    val events by VerificationTracker.events.collectAsState()
    val last   = events.lastOrNull()

    AnimatedVisibility(
        visible = last != null,
        enter   = fadeIn(),
        exit    = fadeOut(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .wrapContentWidth(Alignment.End)
    ) {
        if (last != null) {
            DebugCard(event = last)
        }
    }
}

@Composable
private fun DebugCard(event: VerificationEvent) {
    val typeColor = when {
        event.wasCut       -> RED
        event.type == "FAST" -> GREEN
        else               -> BLUE
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
        shape  = RoundedCornerShape(8.dp),
        modifier = Modifier.wrapContentSize()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "● ${event.type}",
                    color      = typeColor,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MONO
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    event.queryType,
                    color    = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = MONO
                )
            }
            Spacer(Modifier.height(2.dp))
            OverlayRow("Latency", "${event.latencyMs} ms",
                if (event.latencyMs < 100) GREEN else if (event.latencyMs < 3000) BLUE else RED)
            OverlayRow("P50/P90", "${VerificationTracker.p50LatencyMs()}/${VerificationTracker.p90LatencyMs()} ms", BLUE)
            OverlayRow("Tokens",  "${event.tokens}",    Color.White)
            OverlayRow("Cut",     "${event.wasCut}",    if (event.wasCut) RED else Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun OverlayRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, fontFamily = MONO,
            modifier = Modifier.width(44.dp))
        Text(value, color = valueColor, fontSize = 10.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold)
    }
}
