package com.airi.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

// ─────────────────────────────────────────────────────────────────────────────
// ExecDiagnosticsScreen — Hybrid Execution layer live diagnostics
//
// Three tabs, all driven by ViewModel-owned StateFlows. No polling, no timers.
//
//  LIVE    — active backend/provider, last-turn metrics, session counters,
//            last error and last fallback context (shown only when present)
//  BUDGET  — per-provider daily token stats, cost estimates, daily cap bar,
//            "reset today" action
//  HISTORY — ExecTransitionEvent ring buffer (last 20, newest first)
//
// Design contract:
//  • Every nullable or optional value is guarded — no crash path exists.
//  • Every tab has an explicit empty state — no blank screen.
//  • All icons, colors, and typography follow the app's "Cosmic" design system.
//  • Built to be readable in 5 years and maintainable in 20.
// ─────────────────────────────────────────────────────────────────────────────

private enum class DiagTab(val label: String) {
    LIVE("Live"), BUDGET("Budget"), HISTORY("History")
}

// File-private color palette — mirrors RuntimeDiagnosticsPanel conventions.
private val ExOk     = CosmicAccent
private val ExWarn   = Color(0xFFFFB74D)   // amber
private val ExError  = Color(0xFFEF5350)   // red
private val ExDim    = Color.White.copy(alpha = 0.55f)
private val ExSubtle = Color.White.copy(alpha = 0.33f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecDiagnosticsScreen(
    viewModel: ChatViewModel,
    onBack:    () -> Unit
) {
    // ── Single point of truth — both flows collected once at the top ──────────
    val execDiag   by viewModel.execDiagnostics.collectAsState()
    val tokenStats by viewModel.tokenAccountant.stats.collectAsState()
    val scope       = rememberCoroutineScope()

    var selectedTab     by remember { mutableStateOf(DiagTab.LIVE) }
    var showResetDialog by remember { mutableStateOf(false) }

    // Trigger a fresh diagnostics snapshot when the screen opens, matching the
    // same pattern used by PerformanceScreen.
    LaunchedEffect(Unit) {
        runCatching { viewModel.onDiagnosticsScreenVisible() }
    }

    // ── Reset confirmation dialog ─────────────────────────────────────────────
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor   = Color(0xFF141C30),
            tonalElevation   = 0.dp,
            title = {
                Text("Reset Token Stats",
                    color      = Color.White,
                    fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Clear today's token usage counters for all providers? " +
                    "This only affects local tracking — it does not affect billing.",
                    color    = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.tokenAccountant.resetToday() }
                    showResetDialog = false
                }) {
                    Text("Reset", color = ExError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = ExDim)
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White)
                    }
                },
                title = {
                    Column {
                        Text(
                            "Execution Diagnostics",
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                            fontSize   = 17.sp
                        )
                        Text(
                            if (execDiag.isStreaming) "● Streaming" else "Idle",
                            color    = if (execDiag.isStreaming) CosmicAccent
                                       else Color.White.copy(alpha = 0.38f),
                            fontSize = 11.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Reset today's token stats",
                            tint = Color.White.copy(alpha = 0.60f)
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
            // ── Tab row ───────────────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor   = Color.Black.copy(alpha = 0.50f),
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
                                        else Color.White.copy(alpha = 0.45f)
                            )
                        }
                    )
                }
            }

            // ── Tab content ───────────────────────────────────────────────────
            when (selectedTab) {
                DiagTab.LIVE    -> LiveTab(state = execDiag)
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

// ─────────────────────────────────────────────────────────────────────────────
// Tab 1: LIVE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LiveTab(state: ExecutionDiagnosticsState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Active Execution ──────────────────────────────────────────────────
        SettingsSurface {
            ExSection(icon = Icons.Outlined.MonitorHeart, title = "Active Execution")
            Spacer(Modifier.height(12.dp))

            ExRow(
                label      = "Status",
                value      = if (state.isStreaming) "STREAMING" else "IDLE",
                valueColor = if (state.isStreaming) ExOk else ExDim
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

        // ── Last Turn metrics — hidden when there has been no turn yet ─────────
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

        // ── Session Reliability counters ──────────────────────────────────────
        SettingsSurface {
            ExSection(icon = Icons.Outlined.Shield, title = "Session Reliability")
            Spacer(Modifier.height(12.dp))

            ExRow("Retries",       state.retryCount.toString(),
                countColor(state.retryCount))
            ExDivider()
            ExRow("Fallbacks",     state.fallbackCount.toString(),
                countColor(state.fallbackCount))
            ExDivider()
            ExRow("Cancellations", state.cancellationCount.toString(),
                if (state.cancellationCount > 0) ExWarn else ExOk)
        }

        // ── Last Error — only rendered when there is an active error context ───
        val hasError = state.lastErrorType != null || state.lastErrorMessage.isNotBlank()
        if (hasError) {
            SettingsSurface {
                ExSection(icon = Icons.Outlined.ErrorOutline,
                    title = "Last Error", tint = ExError)
                Spacer(Modifier.height(12.dp))

                state.lastErrorType?.let { errType ->
                    ExRow("Error Type", errType.name, ExError)
                    ExDivider()
                }
                if (state.lastErrorMessage.isNotBlank()) {
                    ExRow("Message", state.lastErrorMessage, ExWarn)
                }
                if (state.lastCancelReason.isNotBlank()) {
                    ExDivider()
                    ExRow("Cancel Reason", state.lastCancelReason, ExWarn)
                }
            }
        }

        // ── Last Fallback — only rendered when a fallback has occurred ─────────
        val hasFallback = state.lastFallbackFrom.isNotBlank() ||
                          state.lastFallbackTo.isNotBlank()
        if (hasFallback) {
            SettingsSurface {
                ExSection(icon = Icons.Outlined.SwapHoriz,
                    title = "Last Fallback", tint = ExWarn)
                Spacer(Modifier.height(12.dp))

                ExRow(
                    label      = "From",
                    value      = state.lastFallbackFrom
                        .replace("_", " ").uppercase().ifBlank { "—" },
                    valueColor = ExWarn
                )
                ExDivider()
                ExRow(
                    label      = "To",
                    value      = state.lastFallbackTo
                        .replace("_", " ").uppercase().ifBlank { "—" },
                    valueColor = ExOk
                )
                if (state.lastFallbackReason.isNotBlank()) {
                    ExDivider()
                    ExRow("Reason", state.lastFallbackReason, ExDim)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 2: BUDGET
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BudgetTab(
    stats:       Map<CloudProvider, TokenAccountant.ProviderStats>,
    viewModel:   ChatViewModel,
    scope:       CoroutineScope,
    onShowReset: () -> Unit
) {
    val execPrefs       = remember { viewModel.getExecModePrefs() }
    val totalToday      = remember(stats) { stats.values.sumOf { it.totalTokens } }
    val dailyCap        = execPrefs.maxDailyCloudTokens
    val activeProviders = remember(stats) { stats.filter { (_, v) -> v.requestCount > 0 } }

    var showAll by remember { mutableStateOf(false) }

    // When no provider has been used yet, show all providers so the UI is not
    // blank — the user can still see what will be tracked when cloud is used.
    val displayed: Map<CloudProvider, TokenAccountant.ProviderStats> =
        if (showAll) stats else activeProviders.ifEmpty { stats }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Daily budget overview ─────────────────────────────────────────────
        SettingsSurface {
            ExSection(icon = Icons.Outlined.Timeline, title = "Daily Budget")
            Spacer(Modifier.height(12.dp))

            ExRow("Total Tokens Today", totalToday.toString())

            if (dailyCap > 0) {
                val usedPct = ((totalToday.toFloat() / dailyCap) * 100f)
                    .coerceIn(0f, 100f)
                val barColor = when {
                    usedPct >= 90f -> ExError
                    usedPct >= 70f -> ExWarn
                    else           -> ExOk
                }
                ExDivider()
                ExRow("Daily Cap", "$dailyCap tokens", barColor)
                ExDivider()
                Text(
                    "Used ${"%.1f".format(usedPct)}% of daily cap",
                    color    = ExDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Spacer(Modifier.height(5.dp))
                // Horizontal budget bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                ) {
                    val fraction = (usedPct / 100f).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(barColor)
                        )
                    }
                }
            } else {
                ExDivider()
                ExRow("Daily Cap", "Not configured", ExSubtle)
            }
        }

        // ── Per-provider stats ────────────────────────────────────────────────
        displayed.forEach { (provider, provStats) ->
            ProviderStatsCard(provider = provider, stats = provStats)
        }

        // Toggle to reveal inactive providers (only shown when some are hidden)
        if (activeProviders.size < CloudProvider.entries.size) {
            TextButton(
                onClick  = { showAll = !showAll },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (showAll) "Show active providers only"
                    else         "Show all ${CloudProvider.entries.size} providers",
                    color    = ExDim,
                    fontSize = 12.sp
                )
            }
        }

        // ── Reset action ──────────────────────────────────────────────────────
        OutlinedButton(
            onClick  = onShowReset,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = ExError),
            border   = BorderStroke(1.dp, ExError.copy(alpha = 0.45f))
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Reset Today's Stats", fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProviderStatsCard(
    provider: CloudProvider,
    stats:    TokenAccountant.ProviderStats
) {
    val hasActivity = stats.requestCount > 0

    SettingsSurface {
        // Header row
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasActivity) CosmicAccent.copy(alpha = 0.14f)
                        else Color.White.copy(alpha = 0.04f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = provider.name.first().toString(),
                    color      = if (hasActivity) CosmicAccent else ExSubtle,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    provider.name,
                    fontWeight = FontWeight.Bold,
                    color      = if (hasActivity) Color.White else ExDim,
                    fontSize   = 13.sp
                )
                Text(
                    if (hasActivity)
                        "${stats.requestCount} req · ${stats.failureCount} failed"
                    else
                        "No activity today",
                    color    = ExSubtle,
                    fontSize = 11.sp
                )
            }
            if (!hasActivity) {
                Text("—", color = ExSubtle, fontSize = 13.sp)
            }
        }

        // Detail rows — rendered only when the provider has been used
        if (hasActivity) {
            Spacer(Modifier.height(10.dp))
            ExDivider()

            ExRow("Prompt Tokens",     stats.promptTokens.toString())
            ExDivider()
            ExRow("Completion Tokens", stats.completionTokens.toString())
            ExDivider()
            ExRow("Total Tokens",      stats.totalTokens.toString(), ExOk)
            ExDivider()
            ExRow("Avg Latency",
                if (stats.avgLatencyMs > 0L) "${stats.avgLatencyMs} ms" else "—")
            ExDivider()

            val cost = stats.estimatedCostUsd(provider)
            ExRow(
                label      = "Est. Cost Today",
                value      = when {
                    cost < 0.0001 -> "< \$0.0001"
                    else          -> "\$%.4f".format(cost)
                },
                valueColor = when {
                    cost > 0.50 -> ExError
                    cost > 0.10 -> ExWarn
                    else        -> ExOk
                }
            )

            if (stats.failureCount > 0) {
                ExDivider()
                ExRow("Failures", stats.failureCount.toString(), ExError)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 3: HISTORY
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(history: List<ExecTransitionEvent>) {
    if (history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.padding(horizontal = 32.dp)
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint     = ExSubtle,
                    modifier = Modifier.size(42.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No transitions recorded",
                    color      = ExSubtle,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Backend transitions appear here when the execution layer " +
                    "switches between local and cloud runtimes, or when a failover occurs.",
                    color      = ExSubtle.copy(alpha = 0.65f),
                    fontSize   = 12.sp,
                    textAlign  = TextAlign.Center,
                    lineHeight = 17.sp
                )
            }
        }
        return
    }

    // Newest events first — `reversed()` creates a read-only view, no copy.
    val reversed = remember(history) { history.reversed() }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "header") {
            Text(
                "${history.size} transition${if (history.size != 1) "s" else ""} · newest first",
                color    = ExSubtle,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(
            items = reversed,
            key   = { ev -> "${ev.timestampMs}_${ev.fromBackend}_${ev.toBackend}" }
        ) { event ->
            TransitionEventRow(event)
        }
    }
}

@Composable
private fun TransitionEventRow(event: ExecTransitionEvent) {
    val isFallback = event.fromBackend.isNotBlank() &&
                     event.fromBackend.lowercase() != "none" &&
                     event.fromBackend != event.toBackend

    Surface(
        shape    = RoundedCornerShape(10.dp),
        color    = if (isFallback) Color(0xFF2A1F10) else Color(0xFF0C1A2A),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector        = if (isFallback) Icons.Outlined.SwapHoriz
                                     else Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint     = if (isFallback) ExWarn else ExOk,
                modifier = Modifier
                    .size(16.dp)
                    .padding(top = 1.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                // from → to
                Text(
                    buildString {
                        val from = event.fromBackend
                            .replace("_", " ").uppercase().ifBlank { "NONE" }
                        val to   = event.toBackend
                            .replace("_", " ").uppercase().ifBlank { "NONE" }
                        append(from)
                        append("  →  ")
                        append(to)
                    },
                    color      = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 12.sp
                )
                // Reason (optional)
                if (event.reason.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        event.reason,
                        color      = ExDim,
                        fontSize   = 11.sp,
                        maxLines   = 3,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )
                }
                // Timestamp + origin
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        event.formattedTime,
                        color      = ExSubtle,
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "origin: ${event.origin.name}",
                        color    = ExSubtle,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared primitive composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExSection(
    icon:  ImageVector,
    title: String,
    tint:  Color = ExOk
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint,
            modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, color = tint, fontSize = 13.sp)
    }
}

@Composable
private fun ExRow(
    label:      String,
    value:      String,
    valueColor: Color = ExOk
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label,
            color    = ExDim,
            fontSize = 12.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp))
        Text(value,
            color      = valueColor,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ExDivider() {
    Divider(
        color    = Color.White.copy(alpha = 0.05f),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure helpers — no Compose state, safe to call from remember blocks
// ─────────────────────────────────────────────────────────────────────────────

private fun backendColor(backend: String): Color = when {
    backend.contains("cloud", ignoreCase = true) -> CosmicAccent
    backend.contains("local", ignoreCase = true) -> Color(0xFF4CAF50)
    else                                         -> ExSubtle
}

private fun originColor(origin: String): Color = when (origin.uppercase()) {
    "CLOUD" -> CosmicAccent
    "LOCAL" -> Color(0xFF4CAF50)
    "NONE"  -> ExSubtle
    else    -> ExDim
}

private fun countColor(count: Int): Color = when {
    count == 0 -> ExOk
    count <= 2 -> ExWarn
    else       -> ExError
}

private fun formatMs(ms: Long): String = when {
    ms <= 0L     -> "—"
    ms < 1_000L  -> "$ms ms"
    ms < 60_000L -> "%.1f s".format(ms / 1_000.0)
    else         -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
}
