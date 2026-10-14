package com.airi.assistant.ui.trace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.plan.ExecutionTracePresentation
import com.airi.assistant.ui.plan.ExecutionTraceSummaryStatus
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import androidx.compose.ui.res.stringResource

/**
 * Inline execution trace for the current conversation.
 *
 * This intentionally renders only sanitized execution summaries from
 * ExecutionStatusBus. It never renders model reasoning or raw tool arguments.
 */
@Composable
fun InlineExecutionTrace(
    executionId: String,
    entries: List<ExecutionTraceEvent>,
    isWorking: Boolean,
    modifier: Modifier = Modifier,
) {
    if (executionId.isBlank() || entries.isEmpty()) return

    var expanded by rememberSaveable(executionId) { mutableStateOf(true) }
    val visibleEntries = entries.takeLast(8)
    val active = ExecutionTracePresentation.latestActiveEvent(entries)
    val summaryStatus = ExecutionTracePresentation.summaryStatus(entries, isWorking)
    val activeLabel = if (active == null) "" else stringResource(active.kind.labelRes())
    val activeSubject = active?.displaySubject()
    val accent = when (summaryStatus) {
        ExecutionTraceSummaryStatus.FAILED -> SemanticError
        ExecutionTraceSummaryStatus.RUNNING -> CosmicAccent
        ExecutionTraceSummaryStatus.CANCELLED -> SemanticWarn
        ExecutionTraceSummaryStatus.COMPLETED -> SemanticSuccess
    }
    val title = stringResource(R.string.agent_plan_trace_title)
    val status = when (summaryStatus) {
        ExecutionTraceSummaryStatus.RUNNING -> stringResource(R.string.agent_plan_stage_executing)
        ExecutionTraceSummaryStatus.FAILED -> stringResource(R.string.agent_plan_stage_failed)
        ExecutionTraceSummaryStatus.CANCELLED -> stringResource(R.string.agent_plan_stage_cancelled)
        ExecutionTraceSummaryStatus.COMPLETED -> stringResource(R.string.agent_plan_stage_completed)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AiriTheme.surfaceVariant.copy(alpha = 0.78f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .animateContentSize()
            .semantics {
                paneTitle = title
                contentDescription = "$title · $status · ${visibleEntries.size}"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(accent = accent, active = isWorking)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = AiriTheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(status, color = accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = visibleEntries.size.toString(),
                color = AiriTheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = AiriTheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                visibleEntries.forEach { event ->
                    InlineTraceEventRow(event)
                }
            }
        }

        // The current active item is visible even before a terminal event arrives.
        if (isWorking && active != null) {
            Text(
                text = listOfNotNull(activeLabel.takeIf { it.isNotBlank() }, activeSubject)
                    .joinToString(" · "),
                color = CosmicAccent,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 42.dp, end = 14.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun InlineTraceEventRow(event: ExecutionTraceEvent) {
    val color = event.kind.traceColor()
    val label = stringResource(event.kind.labelRes())
    val subject = event.displaySubject()
    val description = stringResource(R.string.agent_plan_trace_entry_cd, event.sequence.toInt(), label)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(event.kind.icon(), contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = AiriTheme.onSurface,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subject?.let {
                Text(
                    text = it,
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        event.durationMs?.takeIf { it >= 0L }?.let {
            Text(
                text = stringResource(R.string.agent_plan_trace_duration_ms, it),
                color = AiriTheme.onSurfaceVariant,
                fontSize = 9.sp,
            )
        }
    }
}

private fun ExecutionTraceEvent.displaySubject(): String? = when (kind) {
    ExecutionTraceKind.TOOL_STARTED -> summary.substringAfter("Running tool: ", "").takeIf(String::isNotBlank)
    ExecutionTraceKind.TOOL_COMPLETED -> summary.substringAfter("Tool completed: ", "").takeIf(String::isNotBlank)
    ExecutionTraceKind.TOOL_FAILED -> summary.substringAfter("Tool failed: ", "").takeIf(String::isNotBlank)
    ExecutionTraceKind.TOOL_CANCELLED -> summary.substringAfter("Tool cancelled: ", "").takeIf(String::isNotBlank)
    else -> null
}

@Composable
private fun StatusDot(accent: Color, active: Boolean) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (active) 1f else 0.75f))
    )
}

private fun ExecutionTraceKind.labelRes(): Int = when (this) {
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
}

private fun ExecutionTraceKind.traceColor(): Color = when (this) {
    ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> SemanticError
    ExecutionTraceKind.COMPLETED, ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.STEP_COMPLETED -> SemanticSuccess
    ExecutionTraceKind.RECOVERING -> SemanticWarn
    else -> CosmicAccent
}

private fun ExecutionTraceKind.icon() = when (this) {
    ExecutionTraceKind.PLANNING, ExecutionTraceKind.STEP_STARTED, ExecutionTraceKind.TOOL_STARTED -> Icons.Outlined.PlayArrow
    ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.STEP_COMPLETED, ExecutionTraceKind.COMPLETED -> Icons.Outlined.CheckCircle
    ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> Icons.Outlined.ErrorOutline
    ExecutionTraceKind.TOOL_CANCELLED, ExecutionTraceKind.CANCELLED -> Icons.Outlined.PauseCircleOutline
    ExecutionTraceKind.RECOVERING -> Icons.Outlined.HourglassEmpty
    ExecutionTraceKind.REFLECTING -> Icons.Outlined.HourglassEmpty
}
