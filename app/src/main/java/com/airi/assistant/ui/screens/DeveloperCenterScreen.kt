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
import com.airi.assistant.crash.RuntimeHealthMonitor
import com.airi.assistant.ui.activity.AgentActivityBus
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * DeveloperCenterScreen — AIRI internal tooling dashboard.
 *
 * Tabs:
 *  1. Runtime     — orchestration/agent activity, execution bus state
 *  2. Connectors  — health states for all registered connectors
 *  3. Memory      — token usage, cache size, embedding count
 *  4. Diagnostics — latest diagnostic report from AiriDiagnosticEngine
 *  5. Health      — RuntimeHealthMonitor live report (Task 24)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperCenterScreen(onBack: () -> Unit) {
    val tabs = listOf("Runtime", "Connectors", "Memory", "Diagnostics", "Health", "Audit")
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer_center_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null, tint = AiriTheme.onBackground) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = Color.Transparent,
                contentColor     = CosmicAccent,
                divider = { Divider(color = AiriTheme.outline) }
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
                4 -> HealthTab()
                5 -> AuditLogTab()
            }
        }
    }
}

// ── Tab 1: Runtime ─────────────────────────────────────────────────────────────
@Composable
private fun RuntimeTab() {
    val events by AgentActivityBus.recentEvents.collectAsStateWithLifecycle()
    val busState by com.airi.assistant.core.ExecutionStatusBus.status.collectAsStateWithLifecycle()
    val registeredAgents = remember { com.airi.assistant.agent.subagent.SubAgentRegistry.getAll() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        DevCard(title = "Execution Bus") {
            DevRow("Stage",  busState.executionStage.name)
            DevRow("Action", busState.currentAction.take(60).ifBlank { "—" })
            DevRow("Goal",   busState.activeGoalDescription.take(60).ifBlank { "—" })
            DevRow("Retries",busState.retryCount.toString())
        }

        DevCard(title = "Registered Agents (${registeredAgents.size})") {
            registeredAgents.forEach { agent ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(SemanticSuccess))
                    Text(agent.capability.agentId, fontSize = 12.sp, color = AiriTheme.onBackground.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                    Text(agent.capability.description.take(40), fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.35f))
                }
            }
        }

        DevCard(title = "Recent Activity") {
            events.take(10).forEach { event ->
                Row(modifier = Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(event.category.emoji, fontSize = 11.sp)
                    Text(event.message.take(80), fontSize = 10.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 14.sp, modifier = Modifier.weight(1f))
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
            Surface(shape = RoundedCornerShape(12.dp), color = AiriTheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape)
                        .background(if (entry.isConnected) SemanticSuccess else SemanticError))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                        Text(
                            if (entry.isConnected) "Online" else (entry.errorMessage?.take(50) ?: "Offline"),
                            fontSize = 11.sp,
                            color = if (entry.isConnected) SemanticSuccess.copy(0.8f) else SemanticError.copy(0.7f)
                        )
                    }
                    Text(entry.connectorId, fontSize = 10.sp, color = AiriTheme.outline.copy(alpha = 0.25f), fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (healthSummary.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.developer_no_connector_health), color = AiriTheme.onSurfaceVariant.copy(alpha = 0.3f), fontSize = 13.sp) }
            }
        }
    }
}

// ── Tab 3: Memory ──────────────────────────────────────────────────────────────
@Composable
private fun MemoryTab() {
    val runtime  = Runtime.getRuntime()
    val usedMb   = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L
    val maxMb    = runtime.maxMemory() / 1_048_576L
    val usedPct  = (usedMb.toFloat() / maxMb * 100).toInt()

    var memoryCount    by remember { mutableStateOf<Int?>(null) }
    var sessionCount   by remember { mutableStateOf<Int?>(null) }
    var embeddingReady by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val mgr = com.airi.assistant.core.ServiceLocator.memoryManager
                memoryCount    = mgr.getMessageCount()
                sessionCount   = mgr.getAllSessions().size
                embeddingReady = mgr.isSemanticMemoryReady()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        DevCard(title = "AI Memory (Room Database)") {
            DevRow("Messages stored", memoryCount?.toString() ?: "loading…")
            DevRow("Sessions",        sessionCount?.toString() ?: "loading…")
            DevRow("Semantic search", when (embeddingReady) {
                true  -> "✓ Active — embedding model loaded"
                false -> "✗ Inactive — no embedding model"
                null  -> "loading…"
            })
        }

        DevCard(title = "JVM Heap") {
            DevRow("Used",   "$usedMb MB ($usedPct%)")
            DevRow("Max",    "$maxMb MB")
            DevRow("Free",   "${runtime.freeMemory() / 1_048_576L} MB")
            LinearProgressIndicator(
                progress   = usedPct / 100f,
                modifier   = Modifier.fillMaxWidth().padding(top = 6.dp),
                color      = if (usedPct > 80) SemanticError else CosmicAccent,
                trackColor = Color.White.copy(0.1f)
            )
        }
    }
}

// ── Tab 4: Diagnostics ─────────────────────────────────────────────────────────
@Composable
private fun DiagnosticsTab() {
    var report  by remember { mutableStateOf<com.airi.assistant.domain.diagnostics.DiagnosticsRunner.DiagnosticsReport?>(null) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        running = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            report = com.airi.assistant.domain.diagnostics.DiagnosticsRunner.runDiagnostics()
        }
        running = false
    }
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.developer_diagnostics), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
            if (running) Text(stringResource(R.string.developer_running), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
            else report?.let { r ->
                Text(if (r.allPassed) "✓ All passed" else "✗ ${r.results.count { !it.passed }} failed",
                    fontSize = 11.sp, color = if (r.allPassed) Color(0xFF30D158) else Color(0xFFFF453A))
            }
        }
        report?.results?.forEach { test ->
            Surface(shape = RoundedCornerShape(8.dp), color = if (test.passed) Color(0xFF1A251A) else Color(0xFF251A1A), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (test.passed) "✓" else "✗", fontSize = 12.sp, color = if (test.passed) Color(0xFF30D158) else Color(0xFFFF453A), fontWeight = FontWeight.Bold)
                        Text(test.name, fontSize = 11.sp, color = AiriTheme.onBackground, fontWeight = FontWeight.Medium)
                    }
                    Text(test.detail, fontSize = 10.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 13.sp, modifier = Modifier.padding(start = 18.dp))
                }
            }
        } ?: if (!running) Text(stringResource(R.string.developer_no_results), fontSize = 12.sp, color = AiriTheme.onSurfaceVariant) else Unit
    }
}

// ── Tab 5: Health (Task 24) ────────────────────────────────────────────────────
@Composable
private fun HealthTab() {
    val health by ServiceLocator.runtimeHealthMonitor.health.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Overall status indicator ──────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (health.isHealthy) Color(0xFF1A251A) else Color(0xFF251A1A),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape)
                    .background(if (health.isHealthy) SemanticSuccess else SemanticError))
                Text(
                    if (health.isHealthy) "Runtime Healthy" else "Runtime Degraded",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (health.isHealthy) SemanticSuccess else SemanticError
                )
            }
        }

        // ── Memory & disk ─────────────────────────────────────────────────────
        DevCard(title = "Resources") {
            DevRow("Heap available",
                if (health.heapAvailableMb >= 0) "${health.heapAvailableMb} MB" else "—")
            DevRow("Disk free",
                if (health.diskFreeMb >= 0) "${health.diskFreeMb} MB" else "—")
            DevRow("Network", if (health.networkConnected) "✓ Online" else "✗ Offline")
            if (health.lowMemoryWarning) {
                Text("⚠ Low heap memory", fontSize = 11.sp, color = SemanticError,
                    modifier = Modifier.padding(top = 4.dp))
            }
            if (health.lowDiskWarning) {
                Text("⚠ Low disk space", fontSize = 11.sp, color = SemanticError,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ── Session ───────────────────────────────────────────────────────────
        DevCard(title = "Session") {
            val ageMin = health.sessionAgeMs / 60_000L
            DevRow("Session age", "${ageMin} min")
            if (health.sessionAgeWarning) {
                Text("⚠ Long session — consider restarting", fontSize = 11.sp,
                    color = Color(0xFFFFB340), modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ── Coroutines ────────────────────────────────────────────────────────
        DevCard(title = "Coroutines") {
            DevRow("Live coroutines", health.liveCoroutineCount.toString())
            if (health.orphanCoroutineWarning) {
                Text("⚠ Potential orphans: ${health.orphanKeys.take(3).joinToString()}",
                    fontSize = 11.sp, color = SemanticError, modifier = Modifier.padding(top = 2.dp))
            } else {
                Text("✓ No orphan coroutines detected", fontSize = 11.sp, color = SemanticSuccess,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ── Agents ────────────────────────────────────────────────────────────
        DevCard(title = "Agent Tasks") {
            DevRow("Stuck agents", health.stuckAgentCount.toString())
            if (health.stuckAgentCount > 0) {
                Text("⚠ Stuck: ${health.stuckAgentIds.take(3).joinToString()}",
                    fontSize = 11.sp, color = SemanticError, modifier = Modifier.padding(top = 2.dp))
            } else {
                Text("✓ All agents responding", fontSize = 11.sp, color = SemanticSuccess,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ── Event bus ─────────────────────────────────────────────────────────
        DevCard(title = "Event Bus") {
            if (health.eventBusSaturated) {
                Text("⚠ Event bus saturated — drain rate lagging behind emit rate",
                    fontSize = 11.sp, color = SemanticError)
            } else {
                Text("✓ Event bus flowing normally", fontSize = 11.sp, color = SemanticSuccess)
            }
        }

        // ── Thermal / SystemHealthCoordinator (T30) ───────────────────────────
        val throttleLevel by ServiceLocator.systemHealthCoordinator.throttleLevel
            .collectAsStateWithLifecycle()
        val isEmergency = ServiceLocator.systemHealthCoordinator.isEmergencyThrottle
        val budgetFraction = ServiceLocator.systemHealthCoordinator.contextBudgetFraction
        DevCard(title = "Thermal Throttle") {
            DevRow("Throttle level", throttleLevel.name)
            DevRow("Context budget",  "${(budgetFraction * 100).toInt()}%")
            DevRow("Emergency stop",  if (isEmergency) "⚠ YES" else "✓ No")
            if (isEmergency) {
                Text(
                    "⚠ Emergency throttle active — model execution paused",
                    fontSize = 11.sp,
                    color    = SemanticError,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // ── Last check timestamp ──────────────────────────────────────────────
        Text(
            "Last check: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(health.timestampMs))}",
            fontSize = 10.sp,
            color = AiriTheme.onSurfaceVariant.copy(alpha = 0.35f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// ── Tab 6: Audit Log (T26) ─────────────────────────────────────────────────────
@Composable
private fun AuditLogTab() {
    var entries by remember { mutableStateOf<List<com.airi.assistant.memory.entity.AuditLogEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                entries = ServiceLocator.auditRepository.getRecent(limit = 100)
            }
        }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CosmicAccent, modifier = Modifier.size(24.dp))
                }
            }
        } else if (entries.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text("No audit events yet", color = AiriTheme.onSurfaceVariant.copy(0.35f), fontSize = 13.sp)
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AiriTheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                entry.tag.take(24),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CosmicAccent,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                    .format(java.util.Date(entry.timestampMs)),
                                fontSize = 9.sp,
                                color = AiriTheme.outline.copy(0.4f),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            "[${entry.level}] ${entry.message.take(120)}",
                            fontSize = 10.sp,
                            color = AiriTheme.onSurfaceVariant,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Reusable dev UI components ────────────────────────────────────────────────
@Composable
private fun DevCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = AiriTheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent)
            Divider(color = AiriTheme.outline.copy(0.5f), modifier = Modifier.padding(bottom = 2.dp))
            content()
        }
    }
}

@Composable
private fun DevRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f))
        Text(value, fontSize = 11.sp, color = AiriTheme.onBackground.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace)
    }
}
