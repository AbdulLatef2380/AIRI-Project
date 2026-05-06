package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.core.ProofLogRepository
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.AgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WarnAmber = Color(0xFFFFB74D)
private val ErrorRed  = Color(0xFFEF5350)
private val DebugGray = Color(0xFFBDBDBD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentLogsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    onTraceSelected: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Traces", "Live AIRI Log")

    val traces         by viewModel.traces.collectAsState()
    val proofEntries   by viewModel.proofLog.collectAsState()
    val isStreaming    by viewModel.isLogStreaming.collectAsState()
    val streamError    by viewModel.logStreamError.collectAsState()

    val sorted = remember(traces) { traces.sortedByDescending { it.timestamp } }

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
                    Column {
                        Text("Agent Logs", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Text(
                            when (selectedTab) {
                                0 -> "${sorted.size} traces recorded"
                                else -> "${proofEntries.size} AIRI_PROOF events"
                            },
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                },
                actions = {
                    if (selectedTab == 0 && sorted.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearLogs() }) {
                            Text("Clear", color = ErrorRed.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    }
                    if (selectedTab == 1) {
                        if (proofEntries.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearProofLog() }) {
                                Text("Clear", color = ErrorRed.copy(alpha = 0.8f), fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = {
                            if (isStreaming) viewModel.stopLogStream() else viewModel.startLogStream()
                        }) {
                            Icon(
                                if (isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = if (isStreaming) "Stop" else "Start",
                                tint = if (isStreaming) WarnAmber else Color(0xFF66BB6A)
                            )
                        }
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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = Color.Black.copy(alpha = 0.45f),
                contentColor     = CosmicAccent,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        Box(
                            Modifier
                                .tabIndicatorOffset(tabPositions[selectedTab])
                                .height(3.dp)
                                .background(CosmicAccent)
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick  = { selectedTab = idx },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    title,
                                    fontSize = 13.sp,
                                    color = if (selectedTab == idx) Color.White else Color.White.copy(alpha = 0.5f)
                                )
                                // Live dot for tab 1 when streaming
                                if (idx == 1 && isStreaming) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF66BB6A))
                                    )
                                }
                            }
                        }
                    )
                }
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "LogTabContent"
            ) { tab ->
                when (tab) {
                    0 -> TracesTab(sorted, viewModel, onTraceSelected)
                    1 -> LiveLogTab(
                        entries     = proofEntries,
                        isStreaming = isStreaming,
                        error       = streamError,
                        onStart     = { viewModel.startLogStream() },
                    )
                    else -> Unit
                }
            }
        }
    }
}

// ── Traces Tab ────────────────────────────────────────────────────────────────

@Composable
private fun TracesTab(
    sorted: List<AgentTrace>,
    viewModel: AgentViewModel,
    onTraceSelected: () -> Unit
) {
    if (sorted.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.ManageHistory,
                    contentDescription = null,
                    tint = CosmicAccent.copy(alpha = 0.3f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("No agent traces yet", color = Color.White.copy(alpha = 0.55f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Traces appear when skills or tasks are executed",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 13.sp
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sorted, key = { it.id }) { trace ->
                TraceListItem(
                    trace   = trace,
                    onClick = {
                        viewModel.selectTrace(trace)
                        onTraceSelected()
                    }
                )
            }
        }
    }
}

// ── Live Log Tab ──────────────────────────────────────────────────────────────

@Composable
private fun LiveLogTab(
    entries:     List<ProofLogRepository.ProofLogEntry>,
    isStreaming: Boolean,
    error:       String?,
    onStart:     () -> Unit,
) {
    var filterFamily by remember { mutableStateOf("ALL") }
    val listState    = rememberLazyListState()
    val clipboard    = LocalClipboardManager.current

    val visible = remember(entries, filterFamily) {
        if (filterFamily == "ALL") entries
        else entries.filter { it.family == filterFamily }
    }

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.size - 1)
        }
    }

    // Auto-start streaming when tab first displayed
    LaunchedEffect(Unit) {
        if (!isStreaming) onStart()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ProofLogRepository.FAMILIES.forEach { family ->
                val sel = family == filterFamily
                FilterChip(
                    selected = sel,
                    onClick  = { filterFamily = family },
                    label = { Text(family, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                        selectedLabelColor     = CosmicAccent,
                        containerColor         = Color.White.copy(alpha = 0.04f),
                        labelColor             = Color.White.copy(alpha = 0.5f),
                    )
                )
            }
        }

        // Error banner
        if (error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ErrorRed.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Stream error: $error",
                    color = ErrorRed,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onStart) { Text("Retry", fontSize = 11.sp, color = CosmicAccent) }
            }
        }

        // Empty state
        if (visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.List,
                        contentDescription = null,
                        tint = CosmicAccent.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (isStreaming) "Waiting for AIRI_PROOF events…"
                        else "Stream paused — press ▶ to start",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 14.sp
                    )
                    if (!isStreaming) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onStart) {
                            Text("Start stream", color = CosmicAccent)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state   = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(visible, key = { it.id }) { entry ->
                    ProofLogRow(
                        entry    = entry,
                        onCopy   = { clipboard.setText(AnnotatedString(entry.rawLine)) }
                    )
                }
            }
        }
    }
}

// ── Row composables ───────────────────────────────────────────────────────────

@Composable
private fun ProofLogRow(
    entry:  ProofLogRepository.ProofLogEntry,
    onCopy: () -> Unit
) {
    val levelColor = when (entry.level) {
        "E"  -> ErrorRed
        "W"  -> WarnAmber
        "D"  -> DebugGray
        else -> Color.White.copy(alpha = 0.85f)
    }
    val levelBg = when (entry.level) {
        "E"  -> ErrorRed.copy(alpha = 0.08f)
        "W"  -> WarnAmber.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val eventColor = when (entry.family) {
        "INF"       -> CosmicAccent
        "GRAPH"     -> Color(0xFFAB47BC)
        "CONNECTOR" -> Color(0xFF26C6DA)
        "CLOUD"     -> Color(0xFF29B6F6)
        "VOICE"     -> Color(0xFF66BB6A)
        "MEMORY"    -> Color(0xFFFF7043)
        else        -> Color.White.copy(alpha = 0.7f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(levelBg)
            .clickable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Level badge
        Text(
            text = entry.level,
            color = levelColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(12.dp)
        )
        Spacer(Modifier.width(6.dp))
        // Timestamp
        Text(
            text = entry.timeLabel.takeLast(12),
            color = Color.White.copy(alpha = 0.25f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(68.dp)
        )
        Spacer(Modifier.width(6.dp))
        // Event name
        Text(
            text = entry.event,
            color = eventColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 80.dp, max = 160.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        // Data
        Text(
            text = entry.data,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // Copy hint
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "Copy",
            tint = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.size(12.dp).padding(start = 2.dp)
        )
    }
}

// ── TraceListItem (unchanged from original) ───────────────────────────────────

@Composable
private fun TraceListItem(
    trace: AgentTrace,
    onClick: () -> Unit
) {
    val timeStr = remember(trace.timestamp) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(trace.timestamp))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (trace.hasErrors) Color(0xFFFF5252).copy(alpha = 0.25f)
                else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (trace.success) CosmicAccent.copy(alpha = 0.15f)
                        else Color(0xFFFF5252).copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (trace.success) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                    contentDescription = null,
                    tint = if (trace.success) CosmicAccent else Color(0xFFFF5252),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trace.originalInput,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = timeStr, color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                    Text("•", color = Color.White.copy(alpha = 0.2f), fontSize = 10.sp)
                    Text(
                        text = "${trace.stepCount} step${if (trace.stepCount != 1) "s" else ""}",
                        color = CosmicAccent.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                    if (trace.hasErrors) {
                        Text("•", color = Color.White.copy(alpha = 0.2f), fontSize = 10.sp)
                        Text("Has errors", color = Color(0xFFFF5252).copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
