package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.accounting.TokenAccountant
import com.airi.assistant.execution.diagnostics.ExecTransitionEvent
import com.airi.assistant.execution.diagnostics.ExecutionDiagnosticsState
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

// ExecDiagnosticsScreen — Hybrid Execution layer live diagnostics
private enum class DiagTab(val label: String) {
    LIVE("Live"), BUDGET("Budget"), HISTORY("History")
}

// File-private color palette
private val ExOk     = CosmicAccent
private val ExWarn   = Color(0xFFFFB74D)   // amber
private val ExError  = Color(0xFFEF5350)   // red

@Composable
private fun exDim() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
@Composable
private fun exSubtle() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.33f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecDiagnosticsScreen(
    viewModel: ChatViewModel,
    onBack:    () -> Unit
) {
    val execDiag   by viewModel.execDiagnostics.collectAsState()
    val tokenStats       by viewModel.tokenAccountant.stats.collectAsState()
    val tokenRateHistory by viewModel.tokenRateHistory.collectAsState()
    val runtimeDiag      by viewModel.runtimeDiagnostics.collectAsState()
    val scope             = rememberCoroutineScope()

    var selectedTab     by remember { mutableStateOf(DiagTab.LIVE) }
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { viewModel.onDiagnosticsScreenVisible() }
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor   = Color(0xFF141C30),
            tonalElevation   = 0.dp,
            title = {
                Text(stringResource(R.string.exec_reset_token_stats),
                    color      = AiriTheme.onBackground,
                    fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Clear today's token usage counters for all providers? " +
                    "This only affects local tracking — it does not affect billing.",
                    color    = AiriTheme.onBackground.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.tokenAccountant.resetToday() }
                    showResetDialog = false
                }) {
                    Text(stringResource(R.string.reset), color = ExError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel), color = exDim())
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Column {
                        Text(
                            "Execution Diagnostics",
                            fontWeight = FontWeight.Bold,
                            color      = AiriTheme.onBackground,
                            fontSize   = 17.sp
                        )
                        Text(
                            if (execDiag.isStreaming) "● Streaming" else "Idle",
                            color    = if (execDiag.isStreaming) CosmicAccent
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            fontSize = 11.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.cd_reset_token_stats),
                            tint = AiriTheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor   = MaterialTheme.colorScheme.background.copy(alpha = 0.50f),
                contentColor     = CosmicAccent,
                edgePadding      = 8.dp
            ) {
                DiagTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick  = { selectedTab = tab },
                        text = {
                            Text(
                                tab.label,
                                fontSize = 11.sp,
                                color = if (selectedTab == tab) CosmicAccent
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    )
                }
            }
            when (selectedTab) {
                DiagTab.LIVE    -> LiveTab(
                    state            = execDiag,
                    tokenRateHistory = tokenRateHistory,
                    kvUsed           = runtimeDiag.kvUsed,
                    kvMax            = runtimeDiag.kvMax
                )
                DiagTab.BUDGET  -> BudgetTab(
                    stats       = tokenStats,
                    viewModel   = viewModel,
                    scope       = scope,
                    onShowReset = { showResetDialog = true }
                )
                DiagTab.HISTORY -> HistoryTab(history = execDiag.transitionHistory)
            }
        }
    }
}

@Composable
private fun LiveTab(
    state:            ExecutionDiagnosticsState,
    tokenRateHistory: List<Float>,
    kvUsed:           Int,
    kvMax:            Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSurface {
            ExSection(icon = Icons.Outlined.MonitorHeart, title = "Active Execution")
            Spacer(Modifier.height(12.dp))

            ExRow(
                label      = "Status",
                value      = if (state.isStreaming) "STREAMING" else "IDLE",
                valueColor = if (state.isStreaming) ExOk else exDim()
            )
            ExDivider()
            ExRow(
                label      = "Backend",
                value      = state.activeBackend
                    .replace("_", " ")
                    .uppercase()
                    .ifBlank { "NONE" },
                valueColor = backendColor(state.activeBackend)
            )
            if (state.activeProvider != null) {
                ExDivider()
                ExRow("Provider", state.activeProvider.name, ExOk)
            }
            ExDivider()
            ExRow(
                label      = "Origin",
                value      = state.activeOrigin.name,
                valueColor = originColor(state.activeOrigin.name)
            )
        }
        val hasTurnData = state.lastPromptTokens     > 0 ||
                          state.lastCompletionTokens > 0 ||
                          state.lastStreamDurationMs > 0L
        if (hasTurnData) {
            SettingsSurface {
                ExSection(icon = Icons.Outlined.Speed, title = "Last Turn")
                Spacer(Modifier.height(12.dp))

                ExRow("Prompt Tokens",     state.lastPromptTokens.toString())
                ExDivider()
                ExRow("Completion Tokens", state.lastCompletionTokens.toString())
                ExDivider()
                ExRow("Stream Duration",   formatMs(state.lastStreamDurationMs))
                if (state.lastProviderLatencyMs > 0L) {
                    ExDivider()
                    ExRow("First Token Latency", "${state.lastProviderLatencyMs} ms")
                }
            }
        }
        SettingsSurface {
            ExSection(icon = Icons.Outlined.ShowChart, title = "Local Throughput")
            Spacer(Modifier.height(8.dp))
            if (tokenRateHistory.isEmpty()) {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No local generation data yet",
                        color    = exSubtle(),
                        fontSize = 12.sp
                    )
                }
            } else {
                val currentTps = tokenRateHistory.last()
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        "%.1f tok/s".format(currentTps),
                        color      = ExOk,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 20.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "(last ${tokenRateHistory.size} ${if (tokenRateHistory.size == 1) "turn" else "turns"})",
                        color    = exSubtle(),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                TpsSparkline(
                    values   = tokenRateHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "%.1f".format(tokenRateHistory.min()),
                        color    = exSubtle(),
                        fontSize = 10.sp
                    )
                    Text(
                        "max %.1f".format(tokenRateHistory.max()),
                        color    = exSubtle(),
                        fontSize = 10.sp
                    )
                }
            }
        }
        if (kvMax > 0) {
            SettingsSurface {
                ExSection(icon = Icons.Outlined.Memory, title = "Context Window")
                Spacer(Modifier.height(10.dp))

                val kvPct = (kvUsed.toFloat() / kvMax.toFloat()).coerceIn(0f, 1f)
                val barColor = when {
                    kvPct < 0.60f -> ExOk
                    kvPct < 0.85f -> ExWarn
                    else           -> ExError
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(kvPct)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(barColor)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "$kvUsed used",
                        color    = barColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "total $kvMax",
                        color    = exSubtle(),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetTab(
    stats:       Map<String, TokenAccountant.ProviderStats>,
    viewModel:   ChatViewModel,
    scope:       CoroutineScope,
    onShowReset: () -> Unit
) {
    if (stats.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No token usage data for today", color = exSubtle(), fontSize = 14.sp)
        }
        return
    }

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(stats.entries.toList()) { entry ->
            val provider = entry.key
            val s        = entry.value
            SettingsSurface {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExSection(icon = Icons.Outlined.AccountBalanceWallet, title = provider.uppercase())
                    val cost = s.promptTokens * 0.000001 + s.completionTokens * 0.000003
                    if (cost > 0) {
                        Text(
                            "est. $${"%.4f".format(cost)}",
                            color      = ExOk,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ExRow("Prompt Tokens",     s.promptTokens.toString())
                ExDivider()
                ExRow("Completion Tokens", s.completionTokens.toString())
                ExDivider()
                ExRow("Total Today",       (s.promptTokens + s.completionTokens).toString(), ExOk)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onShowReset,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ExError.copy(alpha = 0.1f),
                    contentColor   = ExError
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.DeleteSweep, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset All Daily Counters", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HistoryTab(history: List<ExecTransitionEvent>) {
    if (history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No execution events recorded", color = exSubtle(), fontSize = 14.sp)
        }
        return
    }

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(history) { event ->
            Surface(
                color  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
                shape  = RoundedCornerShape(10.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            event.transition.replace("_", " "),
                            color      = if (event.isError) ExError else ExOk,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 12.sp
                        )
                        Text(
                            formatTime(event.timestamp),
                            color    = exSubtle(),
                            fontSize = 10.sp
                        )
                    }
                    if (event.detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            event.detail,
                            color      = exDim(),
                            fontSize   = 11.sp,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// UI Helpers
@Composable
private fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        shape    = RoundedCornerShape(16.dp),
        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content  = { Column(modifier = Modifier.padding(14.dp), content = content) }
    )
}

@Composable
private fun ExSection(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ExRow(label: String, value: String, valueColor: Color = AiriTheme.onBackground) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = exDim(), fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun ExDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color    = MaterialTheme.colorScheme.outline,
        thickness = 0.5.dp
    )
}

@Composable
private fun TpsSparkline(values: List<Float>, modifier: Modifier) {
    val max = values.max().coerceAtLeast(1f)
    Canvas(modifier = modifier) {
        val w      = size.width
        val h      = size.height
        val dx     = w / (values.size - 1).coerceAtLeast(1)
        val path   = Path()

        values.forEachIndexed { i, v ->
            val x = i * dx
            val y = h - (v / max * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path        = path,
            color       = CosmicAccent,
            style       = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private fun formatMs(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.2fs".format(ms / 1000f)
private fun formatTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}
private fun backendColor(b: String) = when (b.lowercase()) {
    "llama_cpp" -> Color(0xFF66BB6A)
    "gemini"    -> Color(0xFF29B6F6)
    "openai"    -> Color(0xFFAB47BC)
    else        -> CosmicAccent
}
private fun originColor(o: String) = when (o.uppercase()) {
    "LOCAL" -> Color(0xFF66BB6A)
    "CLOUD" -> Color(0xFF29B6F6)
    else    -> Color(0xFFAB47BC)
}
