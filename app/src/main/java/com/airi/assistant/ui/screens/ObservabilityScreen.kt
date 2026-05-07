package com.airi.assistant.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.airi.assistant.agent.observability.AgentObservabilityHub.GraphNodeView
import com.airi.assistant.agent.observability.AgentObservabilityHub.ObservabilitySnapshot
import com.airi.assistant.agent.observability.AgentObservabilityHub.TraceSpan
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.event.ExecutionHistoryStore
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import com.airi.assistant.ui.theme.Surface0
import com.airi.assistant.ui.theme.Surface1
import com.airi.assistant.ui.theme.SurfaceFloating
import com.airi.assistant.ui.theme.SurfaceRaised
import com.airi.assistant.voice.VoicePipelineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservabilityScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Events", "Live Hub", "Graph", "Traces")

    Scaffold(
        containerColor = Surface0,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Text("Observability", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = Color.Black.copy(alpha = 0.5f),
                contentColor     = CosmicAccent
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected  = selectedTab == idx,
                        onClick   = { selectedTab = idx },
                        text      = {
                            Text(
                                label,
                                fontSize = 12.sp,
                                color    = if (selectedTab == idx) CosmicAccent else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    )
                }
            }
            when (selectedTab) {
                0 -> EventsTab()
                1 -> LiveHubTab()
                2 -> GraphTab()
                3 -> TracesTab()
            }
        }
    }
}

// ── Events tab ────────────────────────────────────────────────────────────────

@Composable
private fun EventsTab() {
    var entries by remember { mutableStateOf<List<ExecutionHistoryStore.HistoryEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching {
            entries = ServiceLocator.executionHistoryStore.getRecentEntries(80)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                "${entries.size} events",
                color    = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            IconButton(
                onClick = {
                    runCatching { ServiceLocator.executionHistoryStore.clear() }
                    entries = emptyList()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
            }
        }
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No events recorded yet", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier         = Modifier.fillMaxSize(),
                contentPadding   = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.timestamp }) { entry ->
                    EventCard(entry)
                }
            }
        }
    }
}

@Composable
private fun EventCard(entry: ExecutionHistoryStore.HistoryEntry) {
    val (bg, accent) = when (entry.success) {
        true  -> Color(0xFF0F2A1A) to SemanticSuccess
        false -> Color(0xFF2A0F0F) to SemanticError
        null  -> Color(0xFF1A1A2E) to CosmicAccent
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        entry.eventType,
                        color      = accent,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        entry.formattedTime,
                        color      = Color.White.copy(alpha = 0.35f),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (entry.details.isNotBlank()) {
                    Text(
                        entry.details.take(120),
                        color    = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

// ── Live Hub tab ──────────────────────────────────────────────────────────────

@Composable
private fun LiveHubTab() {
    val snapshot by ServiceLocator.observabilityHub.snapshot.collectAsState()

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Orchestrator / Voice ───────────────────────────────────────────────
        item {
            HubCard(title = "Orchestrator & Voice") {
                MetricRow("Orchestrator", snapshot.orchestratorState.name)
                if (snapshot.orchestratorState.name == "RUNNING") {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress   = (snapshot.orchestratorProgress / 100f).coerceIn(0f, 1f),
                        modifier   = Modifier.fillMaxWidth().height(3.dp),
                        color      = CosmicAccent,
                        trackColor = CosmicAccent.copy(alpha = 0.2f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                MetricRow("Voice state", snapshot.voiceState.name)
                if (snapshot.lastSttLatencyMs > 0L) MetricRow("STT latency",    "${snapshot.lastSttLatencyMs}ms")
                if (snapshot.lastTtsFirstByteMs > 0L) MetricRow("TTS first byte","${snapshot.lastTtsFirstByteMs}ms")
                if (snapshot.perceivedLatencyMs > 0L) MetricRow("Perceived RTT", "${snapshot.perceivedLatencyMs}ms")
                if (snapshot.sessionInterruptions > 0) MetricRow("Interruptions", "${snapshot.sessionInterruptions}")
                if (snapshot.sessionVoiceErrors > 0)   MetricRow("Voice errors",  "${snapshot.sessionVoiceErrors}", color = SemanticWarn)
            }
        }

        // ── Session counters ──────────────────────────────────────────────────
        item {
            HubCard(title = "Session") {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusCountChip("Turns",   snapshot.sessionTotalTurns,     CosmicAccent,   Modifier.weight(1f))
                    StatusCountChip("Tools",   snapshot.sessionTotalToolCalls,  SemanticSuccess, Modifier.weight(1f))
                    StatusCountChip("Tokens",  snapshot.sessionTokensConsumed,  SemanticWarn,   Modifier.weight(1f))
                }
            }
        }

        // ── Agent execution counts ────────────────────────────────────────────
        if (snapshot.agentExecutionCounts.isNotEmpty()) {
            item {
                HubCard(title = "Agent Executions") {
                    snapshot.agentExecutionCounts.entries
                        .sortedByDescending { it.value }
                        .forEach { (agentId, count) ->
                            val latency = snapshot.agentLastLatencyMs[agentId]
                            val errCount = snapshot.agentErrorCounts[agentId] ?: 0
                            AgentMetricRow(agentId, count, errCount, latency)
                        }
                }
            }
        }

        // ── Tool call breakdown ───────────────────────────────────────────────
        if (snapshot.toolCallCounts.isNotEmpty()) {
            item {
                HubCard(title = "Tool Calls") {
                    snapshot.toolCallCounts.entries
                        .sortedByDescending { it.value }
                        .take(12)
                        .forEach { (tool, count) ->
                            MetricRow(tool, "$count calls")
                        }
                }
            }
        }

        // ── Memory ────────────────────────────────────────────────────────────
        item {
            HubCard(title = "Memory") {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusCountChip("Episodic",  snapshot.episodicMemoryEntries,  CosmicAccent,    Modifier.weight(1f))
                    StatusCountChip("Semantic",  snapshot.semanticMemoryEntries,  SemanticSuccess, Modifier.weight(1f))
                    StatusCountChip("Long-term", snapshot.longTermMemoryEntries,  SemanticWarn,    Modifier.weight(1f))
                }
            }
        }

        // ── Durable tasks ─────────────────────────────────────────────────────
        item {
            HubCard(title = "Durable Tasks") {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusCountChip("Active",    snapshot.durableTasksActive,    CosmicAccent,    Modifier.weight(1f))
                    StatusCountChip("Done",      snapshot.durableTasksCompleted, SemanticSuccess, Modifier.weight(1f))
                    StatusCountChip("Failed",    snapshot.durableTasksFailed,    SemanticError,   Modifier.weight(1f))
                }
                if (snapshot.durableTaskQueue.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    snapshot.durableTaskQueue.take(5).forEach { task ->
                        MetricRow(task.title.take(40), task.status.name)
                    }
                }
            }
        }

        // ── Recent errors ─────────────────────────────────────────────────────
        if (snapshot.recentErrors.isNotEmpty()) {
            item {
                HubCard(title = "Recent Errors") {
                    snapshot.recentErrors.takeLast(8).reversed().forEach { err ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(err.timestampMs))
                        Row(
                            modifier  = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                time,
                                color      = Color.White.copy(alpha = 0.35f),
                                fontSize   = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier   = Modifier.width(54.dp)
                            )
                            Column {
                                Text(err.agentId, color = SemanticError, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text(err.reason.take(100), color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── Registered agents ─────────────────────────────────────────────────
        if (snapshot.registeredAgents.isNotEmpty()) {
            item {
                HubCard(title = "Registered Agents (${snapshot.registeredAgents.size})") {
                    snapshot.registeredAgents.forEach { cap ->
                        MetricRow(cap.displayName, cap.agentId, color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, color: Color = Color.White.copy(alpha = 0.65f)) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        Text(value, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AgentMetricRow(agentId: String, executions: Int, errors: Int, lastLatencyMs: Long?) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(agentId, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            if (errors > 0) Text("$errors error${if (errors > 1) "s" else ""}", color = SemanticError, fontSize = 9.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$executions runs", color = CosmicAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            if (lastLatencyMs != null) Text("${lastLatencyMs}ms", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Graph tab ─────────────────────────────────────────────────────────────────

@Composable
private fun GraphTab() {
    val snapshot by ServiceLocator.observabilityHub.snapshot.collectAsState()
    val graph = snapshot.graphSnapshot

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            HubCard(title = "Plan Graph") {
                if (graph == null) {
                    Text("No graph yet", color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
                } else {
                    Column {
                        Text(
                            graph.description.take(80),
                            color      = Color.White.copy(alpha = 0.7f),
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusCountChip("Done",    graph.doneNodes,    SemanticSuccess, Modifier.weight(1f))
                            StatusCountChip("Failed",  graph.failedNodes,  SemanticError,   Modifier.weight(1f))
                            StatusCountChip("Skipped", graph.skippedNodes, SemanticWarn,    Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress   = graph.completionFraction.coerceIn(0f, 1f),
                            modifier   = Modifier.fillMaxWidth().height(4.dp),
                            color      = CosmicAccent,
                            trackColor = CosmicAccent.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${(graph.completionFraction * 100).toInt()}% · ${graph.doneNodes + graph.skippedNodes}/${graph.totalNodes} nodes",
                            color      = Color.White.copy(alpha = 0.4f),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        if (graph != null) {
            items(graph.nodes, key = { it.id }) { node ->
                GraphNodeCard(node)
            }
        }
    }
}

@Composable
private fun GraphNodeCard(node: com.airi.assistant.agent.planning.GoalNode) {
    val status = node.status.name
    val (bg, fg) = when (status) {
        "DONE"       -> Color(0xFF173826) to SemanticSuccess
        "RUNNING"    -> Color(0xFF1A2E4A) to CosmicAccent
        "FAILED"     -> Color(0xFF3A1B1B) to SemanticError
        "RECOVERING" -> Color(0xFF3A2F1B) to SemanticWarn
        "SKIPPED"    -> Color(0xFF242424) to Color.White.copy(alpha = 0.45f)
        else         -> Color(0xFF1E1E2E) to Color.White.copy(alpha = 0.65f)
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth()
            ) {
                Text(node.description, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(status, color = fg, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
            Text("action: ${node.activeAction}", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            if (node.dependsOn.isNotEmpty()) {
                Text("depends: ${node.dependsOn.joinToString()}", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            if (node.failReason != null) {
                Text("error: ${node.failReason}", color = SemanticError.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            if (node.attempts > 0) {
                Text("attempts: ${node.attempts}", color = Color.White.copy(alpha = 0.3f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Text("recovery: ${node.recoveryBranch::class.simpleName}", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Traces tab ────────────────────────────────────────────────────────────────

@Composable
private fun TracesTab() {
    val snapshot by ServiceLocator.observabilityHub.snapshot.collectAsState()
    val spans    = snapshot.completedSpans.asReversed()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                "${spans.size} completed spans · ${snapshot.activeSpanCount} active",
                color      = Color.White.copy(alpha = 0.4f),
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (spans.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No trace spans yet", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize(),
                contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(spans, key = { it.spanId }) { span ->
                    TraceSpanCard(span)
                }
            }
        }
    }
}

@Composable
private fun TraceSpanCard(span: TraceSpan) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (span.success) SemanticSuccess else SemanticError
    val bg     = if (span.success) Color(0xFF0F2218) else Color(0xFF2A0F0F)

    Surface(
        shape    = RoundedCornerShape(8.dp),
        color    = bg,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        span.name,
                        color      = Color.White.copy(alpha = 0.85f),
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines   = if (expanded) Int.MAX_VALUE else 1
                    )
                }
                span.durationMs?.let {
                    Text(
                        "${it}ms",
                        color      = accent,
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (expanded && span.attributes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                span.attributes.forEach { (k, v) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "$k: ",
                            color      = Color.White.copy(alpha = 0.35f),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            v.take(120),
                            color      = Color.White.copy(alpha = 0.65f),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            if (span.parentSpanId != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "parent: ${span.parentSpanId}",
                    color      = Color.White.copy(alpha = 0.25f),
                    fontSize   = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

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
                color       = CosmicAccent,
                fontSize    = 10.sp,
                fontWeight  = FontWeight.Bold,
                fontFamily  = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusCountChip(label: String, count: Int, color: Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = SurfaceFloating) {
        Column(
            modifier             = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment  = Alignment.CenterHorizontally
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
