package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.activity.AgentActivityBus
import com.airi.assistant.ui.theme.*

/**
 * DeveloperCenterScreen — AIRI internal tooling dashboard.
 *
 * Tabs:
 *  1. Runtime — orchestration/agent activity, execution bus state
 *  2. Connectors — health states for all registered connectors
 *  3. Memory — token usage, cache size, embedding count
 *  4. Diagnostics — latest diagnostic report from AiriDiagnosticEngine
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperCenterScreen(onBack: () -> Unit) {
    val tabs = listOf("Runtime", "Connectors", "Memory", "Diagnostics")
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer Center", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = CosmicBlack
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = Color.Transparent,
                contentColor     = CosmicAccent,
                divider = { Divider(color = DividerColor) }
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick  = { selectedTab = idx },
                        text = { Text(label, fontSize = 12.sp, color = if (selectedTab == idx) CosmicAccent else Color.White.copy(0.45f)) }
                    )
                }
            }

            when (selectedTab) {
                0 -> RuntimeTab()
                1 -> ConnectorsTab()
                2 -> MemoryTab()
                3 -> DiagnosticsTab()
            }
        }
    }
}

// ── Tab 1: Runtime ─────────────────────────────────────────────────────────────
@Composable
private fun RuntimeTab() {
    val events by AgentActivityBus.recentEvents.collectAsStateWithLifecycle()
    val busState by com.airi.assistant.core.ExecutionStatusBus.status.collectAsStateWithLifecycle()
    // Use real registered agents from SubAgentRegistry, not the deleted AgentCapabilityGraph
    val registeredAgents = remember { com.airi.assistant.agent.subagent.SubAgentRegistry.getAll() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Execution bus state
        DevCard(title = "Execution Bus") {
            DevRow("Stage",  busState.executionStage.name)
            DevRow("Action", busState.currentAction.take(60).ifBlank { "—" })
            DevRow("Goal",   busState.activeGoalDescription.take(60).ifBlank { "—" })
            DevRow("Retries",busState.retryCount.toString())
        }

        // Active agents — real list from SubAgentRegistry
        DevCard(title = "Registered Agents (${registeredAgents.size})") {
            registeredAgents.forEach { agent ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(SemanticSuccess))
                    Text(agent.capability.agentId, fontSize = 12.sp, color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
                    Text(agent.capability.description.take(40), fontSize = 10.sp, color = Color.White.copy(0.35f))
                }
            }
        }

        // Recent activity (last 10)
        DevCard(title = "Recent Activity") {
            events.take(10).forEach { event ->
                Row(modifier = Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(event.category.emoji, fontSize = 11.sp)
                    Text(event.message.take(80), fontSize = 10.sp, color = Color.White.copy(0.65f), lineHeight = 14.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Tab 2: Connectors ──────────────────────────────────────────────────────────
@Composable
private fun ConnectorsTab() {
    val healthSummary by ServiceLocator.connectorHealthMonitor.healthSummary.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(healthSummary, key = { it.connectorId }) { entry ->
            Surface(shape = RoundedCornerShape(12.dp), color = SurfaceRaised, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape)
                        .background(if (entry.isConnected) SemanticSuccess else SemanticError))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text(
                            if (entry.isConnected) "Online" else (entry.errorMessage?.take(50) ?: "Offline"),
                            fontSize = 11.sp,
                            color = if (entry.isConnected) SemanticSuccess.copy(0.8f) else SemanticError.copy(0.7f)
                        )
                    }
                    Text(entry.connectorId, fontSize = 10.sp, color = Color.White.copy(0.25f), fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (healthSummary.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Text("No connector health data", color = Color.White.copy(0.3f), fontSize = 13.sp) }
            }
        }
    }
}

// ── Tab 3: Memory ──────────────────────────────────────────────────────────────
@Composable
private fun MemoryTab() {
    val runtime = Runtime.getRuntime()
    val usedMb  = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L
    val maxMb   = runtime.maxMemory() / 1_048_576L
    val usedPct = (usedMb.toFloat() / maxMb * 100).toInt()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DevCard(title = "JVM Heap") {
            DevRow("Used",   "$usedMb MB ($usedPct%)")
            DevRow("Max",    "$maxMb MB")
            DevRow("Free",   "${runtime.freeMemory() / 1_048_576L} MB")
            LinearProgressIndicator(
                progress        = usedPct / 100f,
                modifier        = Modifier.fillMaxWidth().padding(top = 6.dp),
                color           = if (usedPct > 80) SemanticError else CosmicAccent,
                trackColor      = Color.White.copy(0.1f)
            )
        }
        DevCard(title = "Adaptive Intelligence") {
            val json = remember { ServiceLocator.adaptiveIntelligence.exportSummaryJson() }
            Text(json, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White.copy(0.65f), lineHeight = 15.sp)
        }
    }
}

// ── Tab 4: Diagnostics ─────────────────────────────────────────────────────────
@Composable
private fun DiagnosticsTab() {
    Box(modifier = Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
        Text(
            "Diagnostics unavailable",
            fontSize = 13.sp,
            color    = Color.White.copy(alpha = 0.4f)
        )
    }
}

// ── Reusable dev UI components ────────────────────────────────────────────────
@Composable
private fun DevCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = SurfaceRaised, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent)
            Divider(color = DividerColor.copy(0.5f), modifier = Modifier.padding(bottom = 2.dp))
            content()
        }
    }
}

@Composable
private fun DevRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = Color.White.copy(0.45f))
        Text(value, fontSize = 11.sp, color = Color.White.copy(0.85f), fontFamily = FontFamily.Monospace)
    }
}
