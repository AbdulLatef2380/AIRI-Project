package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.AgentViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.core.ExecutionTraceArchive
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.ui.activity.AgentActivityBus
import android.content.Intent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.airi.assistant.agent.loop.tool.AgentFileCards
import com.airi.assistant.agent.loop.tool.AgentFileWriter
import com.airi.assistant.ui.components.AgentFileCards as AgentFileCardsRow
import com.airi.assistant.ui.trace.InlineExecutionTrace
import com.airi.assistant.ui.trace.executionTraceAdmitted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTraceDetailScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    onOpenMessage: (sessionId: String, messageId: Long) -> Unit = { _, _ -> },
) {
    val trace by viewModel.selectedTrace.collectAsState()

    if (trace == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val t = trace ?: return  // trace was null-checked above; ?: return handles race
    val timeStr = remember(t.timestamp) { formatTraceDate(t.timestamp, withSeconds = true) }
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.agent_logs_copied)
    var confirmDelete by remember { mutableStateOf(false) }
    var stepDetail by remember { mutableStateOf<AgentStep?>(null) }
    val shareTitle = stringResource(R.string.agent_logs_share_chooser)
    val canOpenMessage = !t.inProgress && t.sessionId.isNotBlank() && (t.messageId ?: 0L) > 0L

    // One timeline for logs and chat: the same events the inline chat panel renders. While the run
    // is live they come from the bus; afterwards from the bus history or the per-message archive.
    val liveEvents by ExecutionStatusBus.trace.collectAsState()
    val activityEvents by AgentActivityBus.recentEvents.collectAsState()
    val storedEvents by produceState(emptyList<ExecutionTraceEvent>(), t.executionId, t.messageId) {
        value = withContext(Dispatchers.IO) {
            ExecutionStatusBus.traceSnapshot(t.executionId).ifEmpty {
                t.messageId?.let { id -> ExecutionTraceArchive.getAll(listOf(id))[id] }.orEmpty()
            }
        }
    }
    val events = if (t.executionId.isNotBlank() && liveEvents.firstOrNull()?.executionId == t.executionId) liveEvents else storedEvents
    val hasUnified = remember(t.executionId, events, activityEvents) {
        executionTraceAdmitted(t.executionId, events, activityEvents)
    }
    var rawToggle by remember(t.id) { mutableStateOf<Boolean?>(null) }
    val showRawSteps = rawToggle ?: !hasUnified

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
                        Text(stringResource(R.string.trace_detail_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, fontSize = 16.sp)
                        Text(timeStr, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, t.toClipboardText().take(TRACE_SHARE_MAX_CHARS))
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.agent_logs_share_cd), tint = AiriTheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("trace", t.toClipboardText()))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.agent_logs_copy_cd), tint = AiriTheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.agent_logs_delete_cd), tint = AiriTheme.onSurfaceVariant)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                TraceDetailCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Forum, contentDescription = null,
                            tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trace_user_input_label), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t.originalInput, color = AiriTheme.onBackground.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AIRIShapes.md)
                        .background(
                            if (t.success || t.inProgress) (if (t.inProgress) CosmicAccent else Color(0xFF00C853)).copy(alpha = 0.1f)
                            else Color(0xFFFF5252).copy(alpha = 0.1f)
                        )
                        .border(
                            1.dp,
                            if (t.inProgress) CosmicAccent.copy(alpha = 0.25f)
                            else if (t.success) Color(0xFF00C853).copy(alpha = 0.25f)
                            else Color(0xFFFF5252).copy(alpha = 0.25f),
                            AIRIShapes.md
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t.inProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CosmicAccent
                            )
                        } else {
                            Icon(
                                if (t.success) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                                contentDescription = null,
                                tint = AiriTheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                when {
                                    t.inProgress -> R.string.trace_in_progress
                                    t.success -> R.string.trace_execution_successful
                                    else -> R.string.trace_execution_failed
                                }
                            ),
                            color = if (t.inProgress) CosmicAccent else if (t.success) Color(0xFF00C853) else Color(0xFFFF5252),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        stringResource(R.string.trace_steps_ratio, t.successCount, t.stepCount),
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            if (canOpenMessage) {
                item {
                    OutlinedButton(
                        onClick = { onOpenMessage(t.sessionId, t.messageId ?: 0L) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_logs_open_message), fontSize = 13.sp)
                    }
                }
            }
            if (hasUnified) {
                item {
                    InlineExecutionTrace(
                        executionId = t.executionId,
                        entries = events,
                        isWorking = t.inProgress,
                        activityEvents = activityEvents,
                    )
                }
            }
            if (t.truncated) {
                item {
                    Text(
                        text = pluralStringResource(R.plurals.trace_truncated_note, t.droppedSteps, t.droppedSteps),
                        color = Color(0xFFFFB300).copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
            }
            if (t.model?.isNotBlank() == true || t.provider?.isNotBlank() == true ||
                t.estimatedOutputTokens != null || t.firstVisibleTokenLatencyMs != null
            ) {
                item {
                    TraceDetailCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            t.model?.takeIf { it.isNotBlank() }?.let {
                                TraceMetadataRow(stringResource(R.string.trace_metadata_model), it)
                            }
                            t.provider?.takeIf { it.isNotBlank() }?.let {
                                TraceMetadataRow(stringResource(R.string.trace_metadata_provider), it)
                            }
                            t.estimatedOutputTokens?.let {
                                Text(
                                    stringResource(R.string.trace_metadata_estimated_output_tokens, it),
                                    color = AiriTheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                            t.firstVisibleTokenLatencyMs?.let {
                                Text(
                                    stringResource(R.string.trace_metadata_first_visible_token, it),
                                    color = AiriTheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
            if (t.steps.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (hasUnified) Modifier.clickable { rawToggle = !showRawSteps } else Modifier)
                            .padding(vertical = 2.dp)
                    ) {
                        Icon(Icons.Outlined.Timeline, contentDescription = null,
                            tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trace_execution_steps), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (hasUnified) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(if (showRawSteps) R.string.trace_hide_raw_steps else R.string.trace_show_raw_steps),
                                color = AiriTheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                if (showRawSteps) {
                    itemsIndexed(t.steps) { idx, step ->
                        // One root per item: several siblings in a lazy item would overlap.
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (t.droppedSteps > 0 && idx == t.droppedAtIndex.coerceIn(0, t.steps.size)) {
                                DroppedStepsMarker(t.droppedSteps)
                            }
                            AgentStepCard(index = idx, step = step, onClick = { stepDetail = step })
                            StepFileCards(step)
                        }
                    }
                }
            }
            if (t.finalResult.isNotBlank()) {
                item {
                    TraceDetailCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null,
                                tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.trace_final_result), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = t.finalResult,
                            color = AiriTheme.onBackground.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    stepDetail?.let { step ->
        StepDetailDialog(step = step, onDismiss = { stepDetail = null })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.agent_logs_delete_title)) },
            text = { Text(stringResource(R.string.agent_logs_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteTrace(t)
                    onBack()
                }) { Text(stringResource(R.string.delete), color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun AgentStepCard(index: Int, step: AgentStep, onClick: () -> Unit = {}) {
    val successColor = if (step.success) Color(0xFF00C853) else Color(0xFFFF5252)
    val stepTime = remember(step.timestampMs) {
        step.timestampMs.takeIf { it > 0L }?.let {
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(it))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(AiriTheme.outline)
            .border(1.dp, successColor.copy(alpha = 0.2f), AIRIShapes.md)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(CosmicAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(step.displayName, color = AiriTheme.onBackground.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(step.typeLabel, color = AiriTheme.outline, fontSize = 10.sp)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (step.durationMs > 0) {
                        Text(
                            text = "${step.durationMs}ms",
                            color = AiriTheme.outline,
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        if (step.success) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                        contentDescription = null,
                        tint = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (stepTime != null || step.durationMs > 0L) {
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    stepTime?.let {
                        Text(
                            stringResource(R.string.trace_metadata_step_time, it),
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                    if (step.durationMs > 0L) {
                        Text(
                            stringResource(R.string.trace_metadata_step_duration, step.durationMs),
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // Input params
            if (step.inputParams.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AIRIShapes.xs)
                        .background(AiriTheme.onSurface.copy(alpha = 0.03f))
                        .padding(8.dp)
                ) {
                    Column {
                        Text(stringResource(R.string.trace_input_badge), color = CosmicAccent.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        step.inputParams.forEach { (k, v) ->
                            Text(
                                text = "$k: $v",
                                color = AiriTheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Output
            if (step.outputSummary.isNotBlank() || step.error != null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AIRIShapes.xs)
                        .background(
                            if (step.success) AiriTheme.onSurface.copy(alpha = 0.03f)
                            else Color(0xFFFF5252).copy(alpha = 0.05f)
                        )
                        .padding(8.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(if (step.success) R.string.trace_output_badge else R.string.trace_error_badge),
                            color = if (step.success) CosmicAccent.copy(alpha = 0.5f)
                                    else Color(0xFFFF5252).copy(alpha = 0.7f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = step.error ?: step.outputSummary,
                            color = if (step.success) AiriTheme.onSurface.copy(alpha = 0.7f)
                                    else Color(0xFFFF5252).copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceMetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
        Text(value, color = AiriTheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TraceDetailCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(AiriTheme.onSurface.copy(alpha = 0.05f))
            .border(1.dp, AiriTheme.outline, AIRIShapes.md)
            .padding(14.dp)
    ) {
        Column(content = content)
    }
}

/** Upper bound for the text handed to the share sheet; intents with very large extras can fail. */
private const val TRACE_SHARE_MAX_CHARS = 100_000

@Composable
private fun DroppedStepsMarker(dropped: Int) {
    Text(
        text = pluralStringResource(R.plurals.trace_dropped_steps_marker, dropped, dropped),
        color = Color(0xFFFFB300).copy(alpha = 0.9f),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.xs)
            .background(Color(0xFFFFB300).copy(alpha = 0.08f))
            .padding(10.dp)
    )
}

/** Shows the file a successful `write_file` step produced, when it is still in the agent files folder. */
@Composable
private fun StepFileCards(step: AgentStep) {
    if (!step.success || step.name != AgentFileWriter.TOOL_NAME) return
    val context = LocalContext.current
    val card = remember(step.inputParams) {
        step.inputParams["filename"]?.let {
            AgentFileCards.fromExistingFile(it, java.io.File(context.filesDir, AgentFileWriter.DIRECTORY_NAME))
        }
    } ?: return
    AgentFileCardsRow(cards = listOf(card))
}

/** Everything the log stored for one step, selectable and copyable. */
@Composable
private fun StepDetailDialog(step: AgentStep, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.agent_logs_copied)
    val text = remember(step) {
        buildString {
            appendLine("${step.displayName} (${step.typeLabel})")
            if (step.durationMs > 0L) appendLine("${step.durationMs} ms")
            step.inputParams.forEach { (k, v) -> appendLine("$k: $v") }
            val body = step.error ?: step.outputSummary
            if (body.isNotBlank()) { appendLine(); append(body) }
        }.trimEnd()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(step.displayName, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(text, fontSize = 12.sp, lineHeight = 17.sp)
                }
                if (step.outputSummary.length >= AgentTraceManager.STEP_OUTPUT_CHARS) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.agent_logs_output_limit_note, AgentTraceManager.STEP_OUTPUT_CHARS),
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("trace-step", text))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.agent_logs_copy_cd)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
