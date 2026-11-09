package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.airi.assistant.R
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.AgentViewModel
import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AgentLogsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    onTraceSelected: () -> Unit
) {
    val traces by viewModel.traces.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AgentTrace?>(null) }
    var filter by rememberSaveable { mutableStateOf(RunFilter.ALL) }
    val groups = remember(traces, query, filter) { buildRunList(traces, query, filter) }
    val isEmpty = groups.isEmpty()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.agent_logs), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, fontSize = 16.sp)
                        Text(stringResource(R.string.traces_recorded, traces.size), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (traces.isNotEmpty()) {
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.agent_logs_search_cd), tint = AiriTheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.clear), color = Color(0xFFFF6B6B).copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (searchOpen && traces.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.agent_logs_search_hint), fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
        }
        if (traces.isNotEmpty()) {
            RunFilterChips(selected = filter, onSelect = { filter = it })
        }
        if (isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.ManageHistory,
                        contentDescription = null,
                        tint = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(if (traces.isEmpty()) R.string.agent_logs_no_traces else R.string.agent_logs_no_matches),
                        color = AiriTheme.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (traces.isEmpty()) stringResource(R.string.agent_logs_empty_hint) else "",
                        color = AiriTheme.outline,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { group ->
                    stickyHeader(key = "day-${group.dayStartMs}-${group.bucket}") {
                        RunDayHeader(group)
                    }
                    items(group.runs, key = { it.id }) { trace ->
                        SwipeableRunCard(
                            trace = trace,
                            onClick = {
                                viewModel.selectTrace(trace)
                                onTraceSelected()
                            },
                            onDelete = { pendingDelete = trace }
                        )
                    }
                }
            }
        }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.agent_logs_clear_title)) },
            text = { Text(stringResource(R.string.agent_logs_clear_message, traces.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    query = ""
                    searchOpen = false
                    viewModel.clearLogs()
                }) { Text(stringResource(R.string.clear), color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.agent_logs_delete_title)) },
            text = { Text(stringResource(R.string.agent_logs_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.deleteTrace(target)
                }) { Text(stringResource(R.string.delete), color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun RunFilterChips(selected: RunFilter, onSelect: (RunFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RunFilter.values().forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        stringResource(
                            when (option) {
                                RunFilter.ALL -> R.string.run_filter_all
                                RunFilter.RUNNING -> R.string.run_filter_running
                                RunFilter.FAILED -> R.string.run_filter_failed
                                RunFilter.HAS_FILES -> R.string.run_filter_files
                            }
                        ),
                        fontSize = 12.sp,
                    )
                },
            )
        }
    }
}

@Composable
private fun RunDayHeader(group: RunDayGroup) {
    val label = when (group.bucket) {
        RunDayBucket.TODAY -> stringResource(R.string.run_day_today)
        RunDayBucket.YESTERDAY -> stringResource(R.string.run_day_yesterday)
        RunDayBucket.OTHER -> remember(group.dayStartMs) {
            DateUtils.formatDateTime(
                null, group.dayStartMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_MONTH,
            ).toString()
        }
    }
    Text(
        text = label,
        color = AiriTheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(AiriTheme.background.copy(alpha = 0.9f))
            .padding(vertical = 6.dp)
            .semantics { contentDescription = label },
    )
}

/**
 * Swipe either way to request deletion. The dismiss never commits on its own: it opens the
 * confirmation dialog and the card snaps back, so a stray swipe cannot lose a run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRunCard(trace: AgentTrace, onClick: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDelete()
            false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(AIRIShapes.md)
                    .background(Color(0xFFFF5252).copy(alpha = 0.18f))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.agent_logs_delete_cd),
                    tint = Color(0xFFFF5252),
                )
            }
        },
    ) {
        RunCard(trace = trace, onClick = onClick, onDelete = onDelete)
    }
}

@Composable
private fun StatusBadge(status: RunStatus) {
    val (label, color) = when (status) {
        RunStatus.RUNNING -> stringResource(R.string.run_status_running) to CosmicAccent
        RunStatus.COMPLETED -> stringResource(R.string.run_status_completed) to Color(0xFF2E9E6B)
        RunStatus.FAILED -> stringResource(R.string.run_status_failed) to Color(0xFFFF5252)
    }
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun RunCard(
    trace: AgentTrace,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val timeStr = remember(trace.timestamp) { formatTraceDate(trace.timestamp) }
    val status = remember(trace) { trace.runStatus() }
    val counters = remember(trace.steps) { trace.runCounters() }
    val preview = remember(trace.finalResult) { trace.resultPreview() }
    val durationMs = remember(trace.steps) { trace.runDurationMs() }
    val modelLine = listOfNotNull(
        (trace.resolvedModel ?: trace.model)?.takeIf { it.isNotBlank() },
        (trace.resolvedProvider ?: trace.provider)?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(AiriTheme.surface)
            .border(
                1.dp,
                when (status) {
                    RunStatus.RUNNING -> CosmicAccent.copy(alpha = 0.35f)
                    RunStatus.FAILED -> Color(0xFFFF5252).copy(alpha = 0.25f)
                    RunStatus.COMPLETED -> AiriTheme.outline
                },
                AIRIShapes.md
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (status == RunStatus.FAILED) Color(0xFFFF5252).copy(alpha = 0.15f)
                        else CosmicAccent.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (status == RunStatus.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = CosmicAccent
                    )
                } else {
                    Icon(
                        if (status == RunStatus.COMPLETED) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                        contentDescription = null,
                        tint = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = trace.runTitle(),
                    color = AiriTheme.onBackground.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(status)
                    Text(text = timeStr, color = AiriTheme.outline, fontSize = 11.sp)
                    if (durationMs > 0L) {
                        Text("•", color = AiriTheme.outline.copy(alpha = 0.6f), fontSize = 10.sp)
                        Text(
                            text = DateUtils.formatElapsedTime((durationMs / 1000L).coerceAtLeast(1L)),
                            color = AiriTheme.outline,
                            fontSize = 11.sp
                        )
                    }
                }
                if (modelLine.isNotEmpty()) {
                    Text(
                        text = modelLine,
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (counters.total > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (counters.files > 0) CounterText(pluralStringResource(R.plurals.run_counter_files, counters.files, counters.files))
                        if (counters.commands > 0) CounterText(pluralStringResource(R.plurals.run_counter_commands, counters.commands, counters.commands))
                        if (counters.tools > 0) CounterText(pluralStringResource(R.plurals.run_counter_tools, counters.tools, counters.tools))
                    }
                }
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (trace.truncated || trace.hasErrors) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (trace.truncated) {
                            Text(
                                text = stringResource(R.string.agent_logs_hidden_steps, trace.droppedSteps),
                                color = Color(0xFFFFB300).copy(alpha = 0.85f),
                                fontSize = 11.sp
                            )
                        }
                        if (trace.hasErrors) {
                            Text(
                                text = stringResource(R.string.agent_logs_has_errors),
                                color = Color(0xFFFF5252).copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.agent_logs_delete_cd),
                    tint = AiriTheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CounterText(text: String) {
    Text(text = text, color = CosmicAccent.copy(alpha = 0.8f), fontSize = 11.sp)
}
