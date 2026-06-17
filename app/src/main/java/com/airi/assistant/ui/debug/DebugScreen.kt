package com.airi.assistant.ui.debug

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.core.debug.Diagnostics
import com.airi.assistant.core.debug.RuntimeStore
import com.airi.assistant.domain.verification.VerificationEvent
import com.airi.assistant.domain.verification.VerificationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BG       = Color(0xFF080B18)
private val CARD     = Color(0xFF0F1224)
private val LABEL    = Color(0xFF6B70A0)
private val GREEN    = Color(0xFF4CAF50)
private val RED      = Color(0xFFFF4444)
private val BLUE     = Color(0xFF42A5F5)
private val MONO     = FontFamily.Monospace

@Composable
fun DebugScreen(onBack: () -> Unit) {
    val runtime         by RuntimeStore.state.collectAsState()
    val events          by VerificationTracker.events.collectAsState()
    val systemHealthy   by Diagnostics.systemHealthy.collectAsState()
    var isRunning       by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.BugReport, contentDescription = "debug", tint = GREEN)
            }
            Text(
                "Verification Layer",
                color = GREEN,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    isRunning = true
                },
                enabled = !isRunning
            ) {
                if (isRunning) {
                    CircularProgressIndicator(color = GREEN, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "re-run", tint = GREEN)
                }
            }
        }

        LaunchedEffect(isRunning) {
            if (isRunning) {
                withContext(Dispatchers.Default) { Diagnostics.runDiagnostics() }
                isRunning = false
            }
        }

        Surface(
            color = if (systemHealthy) GREEN.copy(alpha = 0.1f) else RED.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (systemHealthy) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (systemHealthy) GREEN else RED,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    if (systemHealthy) "System Healthy" else "System Integrity Failed",
                    color = if (systemHealthy) GREEN else RED,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Section("Runtime State") {
            MetricRow("Query Type",    runtime.lastQueryType)
            MetricRow("First Token",   if (runtime.firstTokenMs == 0L) "-" else "${runtime.firstTokenMs} ms")
            MetricRow("Total Latency", if (runtime.totalLatencyMs == 0L) "-" else "${runtime.totalLatencyMs} ms")
            MetricRow("P50 / P90",     "${runtime.p50LatencyMs} / ${runtime.p90LatencyMs} ms")
            MetricRow("Tokens/sec",    if (runtime.tokensPerSecond == 0f) "-" else "%.1f".format(runtime.tokensPerSecond))
            BoolRow("Fast Path",   runtime.fastPath,  positiveColor = GREEN)
            BoolRow("Was Cut",     runtime.wasCut,    positiveColor = RED)
            MetricRow("Voice State",   runtime.voiceState)
        }

        Section("Event History (last ${events.size})") {
            if (events.isEmpty()) {
                Text("No events yet. Send a message.", color = LABEL, fontSize = 12.sp)
            } else {
                events.takeLast(8).reversed().forEach { event ->
                    EventRow(event)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = CARD, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title.uppercase(),
                color = LABEL,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = LABEL, fontSize = 12.sp)
        Text(value, color = AiriTheme.onBackground, fontSize = 12.sp, fontFamily = MONO)
    }
}

@Composable
private fun BoolRow(label: String, value: Boolean, positiveColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = LABEL, fontSize = 12.sp)
        Text(
            if (value) "true" else "false",
            color = if (value) positiveColor else Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontFamily = MONO,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EventRow(event: VerificationEvent) {
    val color = when {
        event.wasCut          -> RED
        event.type == "FAST"  -> GREEN
        else                  -> BLUE
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("●", color = color, fontSize = 10.sp)
        Text(event.type, color = color, fontSize = 11.sp, fontFamily = MONO,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        Text(event.queryType, color = LABEL, fontSize = 10.sp, fontFamily = MONO,
            modifier = Modifier.width(72.dp))
        Text("${event.latencyMs}ms", color = AiriTheme.onBackground, fontSize = 10.sp, fontFamily = MONO,
            modifier = Modifier.width(56.dp))
        if (event.wasCut) {
            Text("CUT", color = RED, fontSize = 9.sp, fontFamily = MONO, fontWeight = FontWeight.Bold)
        }
    }
}
