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
            SettingsCategoryHeader(icon = Icons.Outlined.MonitorHeart, title = "Active Execution")
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
                SettingsCategoryHeader(icon = Icons.Outlined.Speed, title = "Last Turn")
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
            SettingsCategoryHeader(icon = Icons.Outlined.ShowChart, title = "Local Throughput")
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
                SettingsCategoryHeader(icon = Icons.Outlined.Memory, title = "Context Window")
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
    stats:       Map<CloudProvider, TokenAccountant.ProviderStats>,
    viewModel:   ChatViewModel,
    scope:       CoroutineScope,
    onShowReset: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Today's Token Usage",
                color = AiriTheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "Local tracking for awareness only. Actual billing is handled by providers.",
                color = exDim(),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        val providers = CloudProvider.entries.filter { it != CloudProvider.CUSTOM && it != CloudProvider.BRAVE }
        items(providers) { provider ->
            val pStats = stats[provider] ?: TokenAccountant.ProviderStats()
            SettingsSurface {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsCategoryHeader(icon = providerIcon(provider), title = provider.displayName)
                    val cost = pStats.estimatedCostUsd(provider)
                    Text(
                        "$%.4f".format(cost),
                        color = if (cost > 0) ExOk else exSubtle(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                ExRow("Prompt Tokens", pStats.promptTokens.toString())
                ExDivider()
                ExRow("Completion Tokens", pStats.completionTokens.toString())
                ExDivider()
                ExRow("Request Count", pStats.requestCount.toString())
                if (pStats.failureCount > 0) {
                    ExDivider()
                    ExRow("Failures", pStats.failureCount.toString(), ExError)
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onShowReset,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ExError),
                border = BorderStroke(1.dp, ExError.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Outlined.DeleteSweep, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset Token Counters")
            }
        }
    }
}

@Composable
private fun HistoryTab(history: List<ExecTransitionEvent>) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No execution events in this session", color = exSubtle())
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(history.reversed()) { event ->
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .padding(top = 4.dp)
                            .clip(CircleShape)
                            .background(backendColor(event.toBackend))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Transition to ${event.toBackend.replace("_", " ")}",
                            color = AiriTheme.onBackground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            event.reason,
                            color = exDim(),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatMs(event.timestamp),
                            color = exSubtle(),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    val finalColor = if (valueColor == Color.Unspecified) exDim() else valueColor
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = exDim(), fontSize = 12.sp)
        Text(value, color = finalColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ExDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun TpsSparkline(values: List<Float>, modifier: Modifier) {
    val color = ExOk
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1f)
        val min = values.min()
        val range = (max - min).coerceAtLeast(0.1f)

        val width = size.width
        val height = size.height
        val dx = width / (values.size - 1)

        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * dx
            val y = height - ((v - min) / range) * height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path  = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private fun backendColor(backend: String) = when {
    backend.contains("LOCAL") -> Color(0xFF66BB6A)
    backend.contains("CLOUD") -> Color(0xFF29B6F6)
    else                      -> CosmicAccent
}

@Composable
private fun originColor(origin: String) = when (origin) {
    "USER"      -> ExOk
    "FALLBACK"  -> ExWarn
    "HYBRID"    -> Color(0xFFAB47BC)
    else        -> exDim()
}

private fun providerIcon(provider: CloudProvider) = when (provider) {
    CloudProvider.OPENAI    -> Icons.Outlined.AutoAwesome
    CloudProvider.ANTHROPIC -> Icons.Outlined.Psychology
    CloudProvider.GEMINI    -> Icons.Outlined.AutoAwesomeMotion
    CloudProvider.OPENROUTER-> Icons.Outlined.Hub
    CloudProvider.KIMI      -> Icons.Outlined.Message
    else                    -> Icons.Outlined.Cloud
}

private fun formatMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else      -> "%.2fs".format(ms / 1000f)
}
