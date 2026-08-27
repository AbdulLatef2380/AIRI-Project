package com.airi.assistant.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.ui.theme.*
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.viewmodel.ExecutionStage

/**
 * /: Agent Plan content for ModalBottomSheet.
 *
 * Extracted from [AgentPlanOverlay] and extended with:
 *   - Live step-status icons (PENDING / RUNNING / DONE / FAILED)
 *   - Elapsed timing display for RUNNING steps ()
 *   - Active tool call sub-items per step ()
 *   - Dismiss handle for ModalBottomSheet
 *
 * [AgentPlanOverlay] is preserved for backward compat on inline/tablet layout paths.
 */
@Composable
fun AgentPlanContent(
    viewModel: AgentPlanViewModel,
    modifier: Modifier = Modifier
) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val stage by viewModel.currentStage.collectAsStateWithLifecycle()
    val goal  by viewModel.goalDescription.collectAsStateWithLifecycle()
    val traceEntries by viewModel.traceEntries.collectAsStateWithLifecycle()
    val traceFilter by viewModel.traceFilter.collectAsStateWithLifecycle()
    val traceAutoScroll by viewModel.traceAutoScroll.collectAsStateWithLifecycle()
    val unreadTraceCount by viewModel.unreadTraceCount.collectAsStateWithLifecycle()
    val showTraceJumpToLatest by viewModel.showTraceJumpToLatest.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val runningIdx = steps.indexOfLast { it.status.isActive }
    LaunchedEffect(runningIdx) { if (runningIdx >= 0) listState.animateScrollToItem(runningIdx) }

    Column(modifier = modifier.padding(bottom = 16.dp)) {

        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ContentPulseDot(color = stageColor(stage), active = stage == ExecutionStage.EXECUTING)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stageLabel(stage),
                    fontSize    = 12.sp,
                    fontWeight  = FontWeight.SemiBold,
                    color       = stageColor(stage)
                )
                if (goal.isNotBlank()) {
                    Text(
                        goal.take(80),
                        fontSize = 12.sp,
                        color    = AiriTheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 2
                    )
                }
            }
            val done = steps.count { it.status == PlanStepStatus.COMPLETED }
            if (steps.isNotEmpty()) {
                Text(
                    "$done / ${steps.size}",
                    fontSize = 11.sp,
                    color    = AiriTheme.onSurface.copy(alpha = 0.45f)
                )
            }
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.agent_plan_dismiss_cd),
                tint     = AiriTheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable { viewModel.collapse() }
            )
        }

        Divider(color = AiriTheme.outline)

        AgentTraceSection(
            entries = traceEntries,
            selectedFilter = traceFilter,
            autoScroll = traceAutoScroll,
            unreadCount = unreadTraceCount,
            showJumpToLatest = showTraceJumpToLatest,
            onSelectFilter = viewModel::setTraceFilter,
            onPauseFollowing = viewModel::pauseTraceAutoScroll,
            onFollowLatest = viewModel::followTraceLatest,
            onObserved = viewModel::markTraceObserved,
        )

        // ── Steps ────────────────────────────────────────────────────────────
        if (steps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.agent_plan_initialising), fontSize = 12.sp, color = AiriTheme.onSurface.copy(alpha = 0.35f))
            }
        } else {
            LazyColumn(
                state   = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(steps, key = { it.id }) { step ->
                    PlanStepRow(step = step)
                    Divider(
                        color     = AiriTheme.outline,
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(horizontal = 14.dp)
                    )
                }
            }
        }
    }
}

// ── Structured execution trace ────────────────────────────────────────────

@Composable
private fun AgentTraceSection(
    entries: List<ExecutionTraceEvent>,
    selectedFilter: ExecutionTraceFilter,
    autoScroll: Boolean,
    unreadCount: Int,
    showJumpToLatest: Boolean,
    onSelectFilter: (ExecutionTraceFilter) -> Unit,
    onPauseFollowing: () -> Unit,
    onFollowLatest: () -> Unit,
    onObserved: (Long) -> Unit,
) {
    val traceListState = rememberLazyListState()
    val latestSequence = entries.lastOrNull()?.sequence

    LaunchedEffect(latestSequence, autoScroll, entries.size) {
        val latestIndex = entries.lastIndex
        if (autoScroll && latestIndex >= 0) {
            traceListState.animateScrollToItem(latestIndex)
            onObserved(entries[latestIndex].sequence)
        }
    }

    val followControlDescription = stringResource(
        if (autoScroll) R.string.agent_plan_trace_pause_follow
        else R.string.agent_plan_trace_follow_latest,
    )

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.agent_plan_trace_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AiriTheme.onSurface,
            )
            Text(
                text = followControlDescription,
                fontSize = 11.sp,
                color = CosmicAccent,
                modifier = Modifier
                    .semantics { contentDescription = followControlDescription }
                    .clickable { if (autoScroll) onPauseFollowing() else onFollowLatest() },
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(ExecutionTraceFilter.values().asList()) { filter ->
                FilterChip(
                    selected = filter == selectedFilter,
                    onClick = { onSelectFilter(filter) },
                    label = { Text(traceFilterLabel(filter), fontSize = 11.sp) },
                )
            }
        }

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.agent_plan_trace_empty),
                fontSize = 11.sp,
                color = AiriTheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(
                state = traceListState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp),
                contentPadding = PaddingValues(bottom = 6.dp),
            ) {
                items(entries, key = { it.sequence }) { entry ->
                    TraceEntryRow(entry)
                }
            }
        }

        if (showJumpToLatest) {
            Text(
                text = stringResource(R.string.agent_plan_trace_jump_to_latest, unreadCount),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = CosmicAccent,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 6.dp)
                    .clickable { onFollowLatest() },
            )
        }
        Divider(color = AiriTheme.outline, thickness = 0.5.dp)
    }
}

@Composable
private fun TraceEntryRow(entry: ExecutionTraceEvent) {
    var expanded by remember(entry.sequence) { mutableStateOf(false) }
    val kindLabel = traceKindLabel(entry.kind)
    val label = "${entry.sequence} · $kindLabel"
    val rowColor = traceKindColor(entry.kind)
    val entryContentDescription = stringResource(
        R.string.agent_plan_trace_entry_cd,
        entry.sequence,
        kindLabel,
    )
    val detailsContentDescription = stringResource(
        if (expanded) R.string.agent_plan_trace_hide_details
        else R.string.agent_plan_trace_show_details,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.detail.isNullOrBlank()) { expanded = !expanded }
            .semantics {
                contentDescription = "$entryContentDescription. $detailsContentDescription"
            }
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = traceKindIcon(entry.kind),
                contentDescription = null,
                tint = rowColor,
                modifier = Modifier.size(15.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 10.sp, color = rowColor, fontWeight = FontWeight.SemiBold)
                Text(entry.summary, fontSize = 12.sp, color = AiriTheme.onSurface, maxLines = 2)
            }
            entry.durationMs?.let { duration ->
                Text(
                    stringResource(R.string.agent_plan_trace_duration_ms, duration),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AiriTheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        if (expanded && !entry.detail.isNullOrBlank()) {
            Text(
                entry.detail,
                fontSize = 11.sp,
                color = AiriTheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier
                    .padding(start = 23.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun traceFilterLabel(filter: ExecutionTraceFilter): String = stringResource(when (filter) {
    ExecutionTraceFilter.ALL -> R.string.agent_plan_trace_filter_all
    ExecutionTraceFilter.PLANNING -> R.string.agent_plan_trace_filter_planning
    ExecutionTraceFilter.STEPS -> R.string.agent_plan_trace_filter_steps
    ExecutionTraceFilter.TOOLS -> R.string.agent_plan_trace_filter_tools
    ExecutionTraceFilter.ERRORS -> R.string.agent_plan_trace_filter_errors
})

@Composable
private fun traceKindLabel(kind: ExecutionTraceKind): String = stringResource(when (kind) {
    ExecutionTraceKind.PLANNING -> R.string.agent_trace_kind_planning
    ExecutionTraceKind.STEP_STARTED -> R.string.agent_trace_kind_step_started
    ExecutionTraceKind.STEP_COMPLETED -> R.string.agent_trace_kind_step_completed
    ExecutionTraceKind.TOOL_STARTED -> R.string.agent_trace_kind_tool_started
    ExecutionTraceKind.TOOL_COMPLETED -> R.string.agent_trace_kind_tool_completed
    ExecutionTraceKind.TOOL_FAILED -> R.string.agent_trace_kind_tool_failed
    ExecutionTraceKind.TOOL_CANCELLED -> R.string.agent_trace_kind_tool_cancelled
    ExecutionTraceKind.RECOVERING -> R.string.agent_trace_kind_recovering
    ExecutionTraceKind.REFLECTING -> R.string.agent_trace_kind_reflecting
    ExecutionTraceKind.COMPLETED -> R.string.agent_trace_kind_completed
    ExecutionTraceKind.FAILED -> R.string.agent_trace_kind_failed
    ExecutionTraceKind.CANCELLED -> R.string.agent_trace_kind_cancelled
})

@Composable
private fun traceKindColor(kind: ExecutionTraceKind): Color = when (kind) {
    ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> SemanticError
    ExecutionTraceKind.TOOL_CANCELLED, ExecutionTraceKind.CANCELLED -> AiriTheme.onSurface.copy(alpha = 0.5f)
    ExecutionTraceKind.RECOVERING -> SemanticWarn
    ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.STEP_COMPLETED, ExecutionTraceKind.COMPLETED -> SemanticSuccess
    else -> CosmicAccent
}

private fun traceKindIcon(kind: ExecutionTraceKind) = when (kind) {
    ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> Icons.Outlined.ErrorOutline
    ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.STEP_COMPLETED, ExecutionTraceKind.COMPLETED -> Icons.Outlined.Check
    ExecutionTraceKind.TOOL_CANCELLED, ExecutionTraceKind.CANCELLED -> Icons.Outlined.Close
    else -> Icons.Outlined.PlayArrow
}

// ── Step row with live timing () ────────────────────────────────────────

@Composable
private fun PlanStepRow(step: PlanStepModel) {
    val statusLabel = planStepStatusLabel(step.status)
    var nowMs by remember(step.id, step.startedAtMs) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(step.id, step.status, step.startedAtMs) {
        if (!step.status.isActive) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$statusLabel: ${step.label}" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status icon
        Box(modifier = Modifier.padding(top = 2.dp)) {
            when (step.status) {
                PlanStepStatus.QUEUED   -> Icon(Icons.Outlined.HourglassEmpty, null,
                    tint = AiriTheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(14.dp))
                PlanStepStatus.RUNNING   -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), color = CosmicAccent, strokeWidth = 1.5.dp)
                PlanStepStatus.COMPLETED -> Icon(Icons.Outlined.Check, null,
                    tint = SemanticSuccess, modifier = Modifier.size(14.dp))
                PlanStepStatus.FAILED    -> Icon(Icons.Outlined.ErrorOutline, null,
                    tint = SemanticError, modifier = Modifier.size(14.dp))
                PlanStepStatus.RETRYING  -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), color = SemanticWarn, strokeWidth = 1.5.dp)
                PlanStepStatus.CANCELLED   -> Icon(Icons.Outlined.PlayArrow, null,
                    tint = AiriTheme.onSurface.copy(alpha = 0.25f), modifier = Modifier.size(14.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    step.label,
                    fontSize   = 12.sp,
                    fontWeight = if (step.status == PlanStepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                    color      = when (step.status) {
                        PlanStepStatus.RUNNING   -> AiriTheme.onSurface
                        PlanStepStatus.COMPLETED -> AiriTheme.onSurface.copy(alpha = 0.55f)
                        PlanStepStatus.FAILED    -> SemanticError.copy(0.85f)
                        else                     -> AiriTheme.onSurface.copy(0.4f)
                    },
                    modifier = Modifier.weight(1f)
                )

                // : Elapsed time for RUNNING step
                if (step.status == PlanStepStatus.RUNNING && step.startedAtMs != null) {
                    val elapsed = ((nowMs - step.startedAtMs) / 1000L).coerceAtLeast(0L)
                    Text(
                        stringResource(R.string.agent_plan_elapsed_seconds, elapsed),
                        fontSize = 10.sp,
                        color    = CosmicAccent.copy(0.55f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // : Active tool call sub-item
            if (step.status == PlanStepStatus.RUNNING && !step.detail.isNullOrBlank()) {
                Text(
                    "→ ${step.detail}",
                    fontSize = 10.sp,
                    color    = CosmicAccent.copy(0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Error detail
            if (step.status == PlanStepStatus.FAILED && !step.subLabel.isNullOrBlank()) {
                Text(
                    step.subLabel,
                    fontSize = 10.sp,
                    color    = SemanticError.copy(0.65f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ContentPulseDot(color: Color, active: Boolean) {
    val alpha by if (active) {
        rememberInfiniteTransition(label = "cpulse").animateFloat(
            initialValue  = 0.4f,
            targetValue   = 1.0f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label         = "cpulseAlpha"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.7f) }
    }
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

@Composable
private fun stageColor(stage: ExecutionStage) = when (stage) {
    ExecutionStage.PLANNING   -> CosmicAccent
    ExecutionStage.EXECUTING  -> CosmicAccent
    ExecutionStage.RECOVERING -> SemanticWarn
    ExecutionStage.REFLECTING -> Color(0xFFB57BFF)
    ExecutionStage.COMPLETED  -> SemanticSuccess
    ExecutionStage.FAILED     -> SemanticError
    ExecutionStage.CANCELLED  -> AiriTheme.onSurface.copy(alpha = 0.5f)
    ExecutionStage.IDLE       -> AiriTheme.onSurface.copy(alpha = 0.4f)
}

@Composable
private fun stageLabel(stage: ExecutionStage) = when (stage) {
    ExecutionStage.PLANNING   -> stringResource(R.string.agent_plan_stage_planning)
    ExecutionStage.EXECUTING  -> stringResource(R.string.agent_plan_stage_executing)
    ExecutionStage.RECOVERING -> stringResource(R.string.agent_plan_stage_recovering)
    ExecutionStage.REFLECTING -> stringResource(R.string.agent_plan_stage_reflecting)
    ExecutionStage.COMPLETED  -> stringResource(R.string.agent_plan_stage_completed)
    ExecutionStage.FAILED     -> stringResource(R.string.agent_plan_stage_failed)
    ExecutionStage.CANCELLED  -> stringResource(R.string.agent_plan_stage_cancelled)
    ExecutionStage.IDLE       -> stringResource(R.string.agent_plan_stage_idle)
}

@Composable
private fun planStepStatusLabel(status: PlanStepStatus) = when (status) {
    PlanStepStatus.QUEUED -> stringResource(R.string.agent_plan_step_queued)
    PlanStepStatus.RUNNING -> stringResource(R.string.agent_plan_step_running)
    PlanStepStatus.COMPLETED -> stringResource(R.string.agent_plan_step_completed)
    PlanStepStatus.FAILED -> stringResource(R.string.agent_plan_step_failed)
    PlanStepStatus.RETRYING -> stringResource(R.string.agent_plan_step_retrying)
    PlanStepStatus.CANCELLED -> stringResource(R.string.agent_plan_step_cancelled)
}
