package com.airi.assistant.ui.debug

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.ContextPressureManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.core.debug.Diagnostics
import com.airi.assistant.core.debug.RuntimeStore
import com.airi.assistant.core.runtime.SessionStatus
import com.airi.assistant.domain.verification.VerificationEvent
import com.airi.assistant.domain.verification.VerificationTracker
import com.airi.assistant.ui.AiriRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BG     = Color(0xFF080B18)
private val CARD   = Color(0xFF0F1224)
private val LABEL  = Color(0xFF6B70A0)
private val GREEN  = Color(0xFF4CAF50)
private val RED    = Color(0xFFFF4444)
private val BLUE   = Color(0xFF42A5F5)
private val AMBER  = Color(0xFFFFB300)
private val ORANGE = Color(0xFFFF6F00)
private val MONO   = FontFamily.Monospace

@Composable
fun DebugScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val runtime       by RuntimeStore.state.collectAsState()
    val events        by VerificationTracker.events.collectAsState()
    val systemHealthy by Diagnostics.systemHealthy.collectAsState()
    var isRunning     by remember { mutableStateOf(false) }

    // ── Context Pressure ───────────────────────────────────────────────────
    val cpm      = remember { runCatching { ServiceLocator.contextPressureManager }.getOrNull() }
    val pressure by (cpm?.pressure
        ?: kotlinx.coroutines.flow.MutableStateFlow(ContextPressureManager.PressureReport()))
        .collectAsState()

    // Local pressure-level history ring buffer (up to 40 entries)
    val pressureHistory = remember { mutableStateListOf<ContextPressureManager.PressureLevel>() }
    LaunchedEffect(pressure.level) {
        if (pressureHistory.lastOrNull() != pressure.level) {
            pressureHistory += pressure.level
            if (pressureHistory.size > 40) pressureHistory.removeAt(0)
        }
    }

    // ── ARM Sessions ───────────────────────────────────────────────────────
    val arm      = remember { runCatching { ServiceLocator.autonomousRuntimeManager }.getOrNull() }
    val sessions by (arm?.sessions
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.airi.assistant.core.runtime.PersistentTaskSession>()))
        .collectAsState()

    LaunchedEffect(isRunning) {
        if (isRunning) {
            withContext(Dispatchers.Default) { Diagnostics.runDiagnostics() }
            isRunning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ─────────────────────────────────────────────────────────
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
            IconButton(onClick = { isRunning = true }, enabled = !isRunning) {
                if (isRunning) {
                    CircularProgressIndicator(color = GREEN, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "re-run", tint = GREEN)
                }
            }
        }

        // ── System health banner ────────────────────────────────────────────
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

        // ── Runtime State ──────────────────────────────────────────────────
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

        // ── Context Pressure ───────────────────────────────────────────────
        Section("Context Pressure") {
            val barColor = pressureLevelColor(pressure.level)
            val barAnim by animateFloatAsState(
                targetValue   = (pressure.usedPercent / 100f).coerceIn(0f, 1f),
                animationSpec = tween(600, easing = FastOutSlowInEasing),
                label         = "pressureBar"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    pressure.level.name,
                    color = barColor, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = MONO
                )
                Text(
                    "${pressure.usedTokens} / ${pressure.maxTokens} tokens  (${pressure.usedPercent}%)",
                    color = LABEL, fontSize = 11.sp, fontFamily = MONO
                )
            }

            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(barAnim)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor)
                )
            }

            MetricRow("Turns",     pressure.turnCount.toString())
            MetricRow("Remaining", "${pressure.remaining} tokens")
            if (pressure.recommendation != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠ ${pressure.recommendation}",
                    color = barColor.copy(alpha = 0.9f),
                    fontSize = 11.sp, fontFamily = MONO
                )
            }

            // Sparkline — colored bars per historical pressure level
            if (pressureHistory.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text("history ", color = LABEL, fontSize = 9.sp, fontFamily = MONO)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        pressureHistory.forEach { lvl ->
                            Box(
                                modifier = Modifier
                                    .size(width = 5.dp, height = 12.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(pressureLevelColor(lvl).copy(alpha = 0.8f))
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Legend
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(GREEN,  "NOMINAL")
                    LegendDot(AMBER,  "WARNING")
                    LegendDot(ORANGE, "CRITICAL")
                    LegendDot(RED,    "OVERFLOW")
                }
            }
        }

        // ── Autonomous Runtime ─────────────────────────────────────────────
        Section("Autonomous Runtime") {
            val running   = sessions.count { it.status == SessionStatus.RUNNING }
            val suspended = sessions.count { it.status == SessionStatus.SUSPENDED }
            val completed = sessions.count { it.status == SessionStatus.COMPLETED }
            val failed    = sessions.count { it.status == SessionStatus.FAILED }

            MetricRow("Total sessions", sessions.size.toString())
            MetricRow("Running",        running.toString())
            MetricRow("Suspended",      suspended.toString())
            MetricRow("Completed",      completed.toString())
            MetricRow("Failed",         failed.toString())

            if (sessions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Divider(color = LABEL.copy(alpha = 0.2f))
                Spacer(Modifier.height(4.dp))
                Text(
                    "RECENT SESSIONS",
                    color = LABEL, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))
                sessions.takeLast(5).reversed().forEach { sess ->
                    val statusColor = when (sess.status) {
                        SessionStatus.RUNNING   -> GREEN
                        SessionStatus.FAILED    -> RED
                        SessionStatus.COMPLETED -> BLUE
                        SessionStatus.SUSPENDED -> AMBER
                        else                    -> LABEL
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                        Text(
                            sess.status.name,
                            color = statusColor, fontSize = 9.sp,
                            fontFamily = MONO, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(68.dp)
                        )
                        Text(
                            sess.goalText.take(60),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text("No ARM sessions yet.", color = LABEL, fontSize = 12.sp)
            }
        }

        // ── Event History ──────────────────────────────────────────────────
        Section("Event History (last ${events.size})") {
            if (events.isEmpty()) {
                Text("No events yet. Send a message.", color = LABEL, fontSize = 12.sp)
            } else {
                events.takeLast(8).reversed().forEach { event ->
                    EventRow(event)
                }
            }
        }

        // ── Diagnostics Panel ──────────────────────────────────────────────
        OutlinedButton(
            onClick = { onNavigate(AiriRoute.DEBUG_PANEL) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GREEN),
            border = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(alpha = 0.35f))
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Default.BugReport,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Open Diagnostics Panel", fontSize = 12.sp, fontFamily = MONO)
        }
    }
}

// ── Composable helpers ────────────────────────────────────────────────────────

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(3.dp))
        Text(label, color = LABEL, fontSize = 8.sp, fontFamily = MONO)
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
        Text(value, color = Color.White, fontSize = 12.sp, fontFamily = MONO)
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
        Text("${event.latencyMs}ms", color = Color.White, fontSize = 10.sp, fontFamily = MONO,
            modifier = Modifier.width(56.dp))
        if (event.wasCut) {
            Text("CUT", color = RED, fontSize = 9.sp, fontFamily = MONO, fontWeight = FontWeight.Bold)
        }
    }
}

private fun pressureLevelColor(level: ContextPressureManager.PressureLevel): Color = when (level) {
    ContextPressureManager.PressureLevel.NOMINAL  -> Color(0xFF4CAF50)
    ContextPressureManager.PressureLevel.WARNING  -> Color(0xFFFFB300)
    ContextPressureManager.PressureLevel.CRITICAL -> Color(0xFFFF6F00)
    ContextPressureManager.PressureLevel.OVERFLOW -> Color(0xFFFF4444)
}
