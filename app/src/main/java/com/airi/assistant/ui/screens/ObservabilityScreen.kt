package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.agent.observability.AgentObservabilityHub.ObservabilitySnapshot
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.event.ExecutionHistoryStore
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import com.airi.assistant.ui.theme.SurfaceFloating
import com.airi.assistant.ui.theme.SurfaceRaised
import com.airi.assistant.voice.VoicePipelineState

// ─────────────────────────────────────────────────────────────────────────────
// ObservabilityScreen — two-tab live runtime observatory
//
//  EVENTS   — existing execution history ring buffer (unchanged)
//  LIVE HUB — real-time AgentObservabilityHub.snapshot StateFlow
//             showing voice pipeline, orchestrator, agent executions,
//             tool calls, memory layers, durable tasks, error ring
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservabilityScreen(onBack: () -> Unit) {

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Events", "Live Hub")

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Text(
                        "Observability",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 17.sp
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = Color.Black.copy(alpha = 0.5f),
                contentColor     = CosmicAccent
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick  = { selectedTab = idx },
                        text = {
                            Text(
                                label,
                                fontSize = 12.sp,
                                color = if (selectedTab == idx) CosmicAccent
                                        else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> EventsTab()
                1 -> LiveHubTab()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 0 — Events (original history view)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventsTab() {
    val historyStore = remember { ServiceLocator.executionHistoryStore }
    var entries      by remember { mutableStateOf(historyStore.getRecentEntries(100)) }
    var filterType   by remember { mutableStateOf("All") }

    val filterOptions = listOf("All", "AgentStarted", "AgentSuccess", "AgentFailed",
        "AgentTimeout", "Skill", "Tool", "Policy", "SignIn", "Sub", "Limit", "Premium")

    val displayed = if (filterType == "All") entries
                    else entries.filter { it.eventType.startsWith(filterType) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScrollableTabRow(
                selectedTabIndex = filterOptions.indexOf(filterType).coerceAtLeast(0),
                containerColor   = Color.Transparent,
                contentColor     = CosmicAccent,
                edgePadding      = 0.dp,
                modifier         = Modifier.weight(1f)
            ) {
                filterOptions.forEach { type ->
                    Tab(
                        selected = filterType == type,
                        onClick  = { filterType = type },
                        text = {
                            Text(
                                text     = type,
                                fontSize = 10.sp,
                                color    = if (filterType == type) CosmicAccent
                                           else Color.White.copy(alpha = 0.4f)
                            )
                        }
                    )
                }
            }
            IconButton(onClick = {
                historyStore.clear()
                entries = emptyList()
            }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Clear",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (displayed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No events recorded", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize(),
                contentPadding      = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayed, key = { it.timestamp.toString() + it.eventType }) { entry ->
                    EventEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun EventEntryRow(entry: ExecutionHistoryStore.HistoryEntry) {
    val (bgColor, textColor) = when {
        entry.success == true  -> Color(0xFF1B3A2D) to SemanticSuccess
        entry.success == false -> Color(0xFF3A1B1B) to SemanticError
        else                   -> Color(0xFF1E1E2E) to CosmicAccent
    }
    Surface(
        shape    = RoundedCornerShape(8.dp),
        color    = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text       = entry.eventType,
                        color      = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = entry.formattedTime,
                        color      = Color.White.copy(alpha = 0.4f),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (entry.details.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text     = entry.details,
                        color    = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        maxLines = 2
                    )
                }
            }
            entry.success?.let { ok ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = if (ok) "✓" else "✗",
                    color      = textColor,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 1 — Live Hub
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LiveHubTab() {
    val snapshot by ServiceLocator.observabilityHub.snapshot.collectAsState()

    LazyColumn(
        modifier        = Modifier.fillMaxSize(),
        contentPadding  = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { VoicePipelineCard(snapshot) }
        item { OrchestratorCard(snapshot) }
        item { AgentExecutionCard(snapshot) }
        item { ToolCallsCard(snapshot) }
        item { MemoryLayerCard(snapshot) }
        item { DurableTasksCard(snapshot) }
        if (snapshot.recentErrors.isNotEmpty()) {
            item { ErrorRingCard(snapshot) }
        }
    }
}

// ── Voice Pipeline Card ───────────────────────────────────────────────────────

@Composable
private fun VoicePipelineCard(snap: ObservabilitySnapshot) {
    val stateColor = when (snap.voiceState) {
        VoicePipelineState.IDLE               -> Color.White.copy(alpha = 0.4f)
        VoicePipelineState.LISTENING          -> SemanticSuccess
        VoicePipelineState.THINKING           -> CosmicAccent
        VoicePipelineState.STREAMING_RESPONSE -> Color(0xFF9B59B6)
        VoicePipelineState.INTERRUPTED        -> SemanticWarn
        VoicePipelineState.RECOVERING         -> SemanticError
    }
    val stateLabel = when (snap.voiceState) {
        VoicePipelineState.IDLE               -> "IDLE"
        VoicePipelineState.LISTENING          -> "LISTENING"
        VoicePipelineState.THINKING           -> "THINKING"
        VoicePipelineState.STREAMING_RESPONSE -> "SPEAKING"
        VoicePipelineState.INTERRUPTED        -> "INTERRUPTED"
        VoicePipelineState.RECOVERING         -> "RECOVERING"
    }
    HubCard(title = "Voice Pipeline") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(stateLabel, color = stateColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Interruptions: ${snap.sessionInterruptions}  Errors: ${snap.sessionVoiceErrors}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LatencyChip("STT", snap.lastSttLatencyMs, Modifier.weight(1f))
            LatencyChip("TTS", snap.lastTtsFirstByteMs, Modifier.weight(1f))
            LatencyChip("E2E", snap.perceivedLatencyMs, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LatencyChip(label: String, ms: Long, modifier: Modifier = Modifier) {
    val color = when {
        ms == 0L   -> Color.White.copy(alpha = 0.3f)
        ms < 300L  -> SemanticSuccess
        ms < 700L  -> SemanticWarn
        else       -> SemanticError
    }
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(6.dp),
        color    = SurfaceFloating
    ) {
        Column(
            modifier              = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment   = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
            Text(
                if (ms == 0L) "—" else "${ms}ms",
                color      = color,
                fontWeight = FontWeight.Bold,
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ── Orchestrator Card ─────────────────────────────────────────────────────────

@Composable
private fun OrchestratorCard(snap: ObservabilitySnapshot) {
    HubCard(title = "Orchestrator") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isRunning = snap.orchestratorState.name == "RUNNING"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) SemanticSuccess else Color.White.copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    snap.orchestratorState.name,
                    color    = if (isRunning) SemanticSuccess else Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Turns: ${snap.sessionTotalTurns}  Tokens: ${snap.sessionTokensConsumed}",
                color  = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (snap.orchestratorProgress > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress         = { snap.orchestratorProgress / 100f },
                modifier         = Modifier.fillMaxWidth().height(3.dp),
                color            = CosmicAccent,
                trackColor       = CosmicAccent.copy(alpha = 0.2f)
            )
        }
    }
}

// ── Agent Execution Card ──────────────────────────────────────────────────────

@Composable
private fun AgentExecutionCard(snap: ObservabilitySnapshot) {
    HubCard(title = "Agent Execution") {
        if (snap.agentExecutionCounts.isEmpty() && snap.registeredAgents.isEmpty()) {
            Text(
                "No agents executed this session",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 12.sp
            )
        } else {
            val allAgentIds = (snap.agentExecutionCounts.keys + snap.agentErrorCounts.keys +
                snap.registeredAgents.map { it.agentId }).toSortedSet()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    TableHeader("Agent", Modifier.weight(2f))
                    TableHeader("OK", Modifier.weight(1f))
                    TableHeader("ERR", Modifier.weight(1f))
                    TableHeader("Latency", Modifier.weight(1.5f))
                }
                allAgentIds.forEach { id ->
                    val ok  = snap.agentExecutionCounts[id] ?: 0
                    val err = snap.agentErrorCounts[id] ?: 0
                    val lat = snap.agentLastLatencyMs[id]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceFloating, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            id.replace("_agent", "").replace("_", " "),
                            Modifier.weight(2f),
                            color    = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Text(
                            "$ok",
                            Modifier.weight(1f),
                            color    = if (ok > 0) SemanticSuccess else Color.White.copy(alpha = 0.3f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "$err",
                            Modifier.weight(1f),
                            color    = if (err > 0) SemanticError else Color.White.copy(alpha = 0.3f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            lat?.let { "${it}ms" } ?: "—",
                            Modifier.weight(1.5f),
                            color      = Color.White.copy(alpha = 0.5f),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ── Tool Calls Card ───────────────────────────────────────────────────────────

@Composable
private fun ToolCallsCard(snap: ObservabilitySnapshot) {
    HubCard(title = "Tool Calls  (total ${snap.sessionTotalToolCalls})") {
        if (snap.toolCallCounts.isEmpty()) {
            Text("No tool calls this session", color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
        } else {
            val sorted = snap.toolCallCounts.entries.sortedByDescending { it.value }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sorted.forEach { (tool, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceFloating, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tool, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Text(
                            "×$count",
                            color      = CosmicAccent,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ── Memory Layer Card ─────────────────────────────────────────────────────────

@Composable
private fun MemoryLayerCard(snap: ObservabilitySnapshot) {
    HubCard(title = "Memory Layers") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MemoryLayerChip("Episodic",  snap.episodicMemoryEntries,  Modifier.weight(1f))
            MemoryLayerChip("Semantic",  snap.semanticMemoryEntries,  Modifier.weight(1f))
            MemoryLayerChip("Long-term", snap.longTermMemoryEntries,  Modifier.weight(1f))
        }
    }
}

@Composable
private fun MemoryLayerChip(label: String, count: Int, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = SurfaceFloating) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
            Text(
                "$count",
                color      = CosmicAccent,
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ── Durable Tasks Card ────────────────────────────────────────────────────────

@Composable
private fun DurableTasksCard(snap: ObservabilitySnapshot) {
    HubCard(title = "Durable Tasks") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCountChip("Active",    snap.durableTasksActive,    SemanticWarn,    Modifier.weight(1f))
            StatusCountChip("Done",      snap.durableTasksCompleted, SemanticSuccess, Modifier.weight(1f))
            StatusCountChip("Failed",    snap.durableTasksFailed,    SemanticError,   Modifier.weight(1f))
        }
        if (snap.durableTaskQueue.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            snap.durableTaskQueue.take(5).forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceFloating, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        task.title.take(28),
                        color    = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                    Text(
                        task.status.name,
                        color      = SemanticWarn,
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

// ── Error Ring Card ───────────────────────────────────────────────────────────

@Composable
private fun ErrorRingCard(snap: ObservabilitySnapshot) {
    HubCard(title = "Recent Errors  (${snap.recentErrors.size})") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            snap.recentErrors.takeLast(8).reversed().forEach { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3A1B1B), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(err.agentId, color = SemanticError, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(err.reason.take(60), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                    }
                    Text(
                        err.formattedTime,
                        color      = Color.White.copy(alpha = 0.3f),
                        fontSize   = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HubCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        color    = SurfaceRaised
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title.uppercase(),
                color      = CosmicAccent,
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun TableHeader(label: String, modifier: Modifier) {
    Text(
        label,
        modifier   = modifier,
        color      = Color.White.copy(alpha = 0.35f),
        fontSize   = 9.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun StatusCountChip(label: String, count: Int, color: Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = SurfaceFloating) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
            Text(
                "$count",
                color      = if (count > 0) color else Color.White.copy(alpha = 0.3f),
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
