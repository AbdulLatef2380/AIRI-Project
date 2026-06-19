package com.airi.assistant.ui.screens

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.domain.diagnostics.DiagnosticsRunner
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.DebugState

private val DarkSurface    = Color(0xFF0D0F1E)
private val CardBg         = Color(0xFF141628)
private val LabelColor     = Color(0xFF7A7FA8)
private val PassGreen      = Color(0xFF4CAF50)
private val FailRed        = Color(0xFFFF4444)
private val MonoFont       = FontFamily.Monospace

@Composable
fun DebugPanelScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val debugState        by viewModel.debugState.collectAsState()
    val integrityFailed   by viewModel.systemIntegrityFailed.collectAsState()
    var diagReport        by remember { mutableStateOf<DiagnosticsRunner.DiagnosticsReport?>(null) }
    var isRunning         by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isRunning = true
        diagReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            DiagnosticsRunner.runDiagnostics()
        }
        isRunning = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.BugReport, contentDescription = stringResource(R.string.cd_debug), tint = CosmicAccent)
            }
            Spacer(Modifier.width(8.dp))
            Text("Debug Panel", color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        if (integrityFailed) {
            Surface(
                color = FailRed.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = FailRed)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "System Integrity Failed",
                        color = FailRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        DebugSection("Last Generation") {
            DebugRow("Model",           debugState.lastModelName)
            DebugRow("Query Type",      debugState.lastQueryType)
            DebugRow("First Token",     if (debugState.lastFirstTokenMs < 0) "-" else "${debugState.lastFirstTokenMs} ms")
            DebugRow("Total Latency",   if (debugState.lastTotalLatencyMs < 0) "-" else "${debugState.lastTotalLatencyMs} ms")
            DebugRow("P50 / P90",       "${debugState.p50LatencyMs} / ${debugState.p90LatencyMs} ms")
            DebugRow("Tokens/sec",      if (debugState.lastTokensPerSec == 0f) "-" else "%.1f".format(debugState.lastTokensPerSec))
            DebugBoolRow("Fast Path",   debugState.lastIsFastPath)
            DebugBoolRow("Was Cut",     debugState.lastWasCut)
        }

        DebugSection("Voice") {
            DebugRow("Current State", debugState.currentVoiceState)
        }

        DebugSection("Diagnostics") {
            if (isRunning) {
                CircularProgressIndicator(
                    color = CosmicAccent,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(4.dp)
                )
            } else {
                val report = diagReport
                if (report == null) {
                    Text("No results", color = LabelColor, fontSize = 12.sp)
                } else {
                    report.results.forEach { result ->
                        DiagTestRow(result)
                    }
                    Spacer(Modifier.height(4.dp))
                    val allPassed = report.allPassed
                    Surface(
                        color = (if (allPassed) PassGreen else FailRed).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (allPassed) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (allPassed) PassGreen else FailRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (allPassed) "All tests passed" else "One or more tests FAILED",
                                color = if (allPassed) PassGreen else FailRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = {
                isRunning = true
                diagReport = null
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Re-run Diagnostics", color = CosmicAccent, fontSize = 12.sp)
        }

        LaunchedEffect(isRunning) {
            if (isRunning && diagReport == null) {
                diagReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    DiagnosticsRunner.runDiagnostics()
                }
                isRunning = false
            }
        }
    }
}

@Composable
private fun DebugSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title.uppercase(),
                color = LabelColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LabelColor, fontSize = 12.sp)
        Text(value, color = AiriTheme.onBackground, fontSize = 12.sp, fontFamily = MonoFont)
    }
}

@Composable
private fun DebugBoolRow(label: String, value: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LabelColor, fontSize = 12.sp)
        Text(
            if (value) "true" else "false",
            color = if (value) PassGreen else Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DiagTestRow(result: DiagnosticsRunner.TestResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (result.passed) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (result.passed) PassGreen else FailRed,
            modifier = Modifier.size(14.dp).padding(top = 1.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                result.name,
                color = if (result.passed) Color.White else FailRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(result.detail, color = LabelColor, fontSize = 10.sp, fontFamily = MonoFont)
        }
    }
}
