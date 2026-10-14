package com.airi.assistant.ui.trace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import androidx.compose.ui.res.stringResource

/**
 * Persistent, inline execution timeline for one assistant turn.
 *
 * This is deliberately not a card, dialog, overlay, or collapsible panel. It
 * is rendered as part of the assistant response so progress remains readable
 * after completion. The input is still the sanitized ExecutionStatusBus trace;
 * model chain-of-thought and raw tool arguments are never rendered here.
 */
@Composable
fun InlineExecutionTrace(
    executionId: String,
    entries: List<ExecutionTraceEvent>,
    isWorking: Boolean,
    modifier: Modifier = Modifier,
) {
    if (executionId.isBlank() || entries.isEmpty()) return

    val summaryStatus = when {
        isWorking -> TraceStatus.RUNNING
        entries.lastOrNull()?.kind == ExecutionTraceKind.FAILED ||
            entries.lastOrNull()?.kind == ExecutionTraceKind.TOOL_FAILED -> TraceStatus.FAILED
        entries.lastOrNull()?.kind == ExecutionTraceKind.CANCELLED ||
            entries.lastOrNull()?.kind == ExecutionTraceKind.TOOL_CANCELLED -> TraceStatus.CANCELLED
        else -> TraceStatus.COMPLETED
    }
    val accent = when (summaryStatus) {
        TraceStatus.RUNNING -> CosmicAccent
        TraceStatus.FAILED -> SemanticError
        TraceStatus.CANCELLED -> SemanticWarn
        TraceStatus.COMPLETED -> SemanticSuccess
    }
    val title = stringResource(R.string.agent_plan_trace_title)
    val status = when (summaryStatus) {
        TraceStatus.RUNNING -> stringResource(R.string.agent_plan_stage_executing)
        TraceStatus.FAILED -> stringResource(R.string.agent_plan_stage_failed)
        TraceStatus.CANCELLED -> stringResource(R.string.agent_plan_stage_cancelled)
        TraceStatus.COMPLETED -> stringResource(R.string.agent_plan_stage_completed)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)
            .semantics { contentDescription = "$title · $status · ${entries.size}" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(accent = accent, active = isWorking)
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = AiriTheme.onSurfaceVariant.copy(alpha = 0.78f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = status,
                color = accent.copy(alpha = 0.9f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        entries.forEachIndexed { index, event ->
            InlineTraceEventRow(
                event = event,
                accent = accent,
                drawConnector = index < entries.lastIndex,
            )
        }
    }
}

@Composable
private fun InlineTraceEventRow(
    event: ExecutionTraceEvent,
    accent: Color,
    drawConnector: Boolean,
) {
    val color = event.kind.traceColor(accent)
    val label = stringResource(event.kind.labelRes())
    val description = stringResource(R.string.agent_plan_trace_entry_cd, event.sequence.toInt(), label)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(event.kind.icon(), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            if (drawConnector) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .width(1.dp)
                        .height(12.dp)
                        .background(AiriTheme.outline.copy(alpha = 0.28f))
                )
            }
        }
        Text(
            text = event.summary,
            color = AiriTheme.onSurface.copy(alpha = 0.86f),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(top = 1.dp),
        )
        event.durationMs?.takeIf { it >= 0L }?.let {
            Text(
                text = stringResource(R.string.agent_plan_trace_duration_ms, it),
                color = AiriTheme.onSurfaceVariant.copy(alpha = 0.66f),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private enum class TraceStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

private fun ExecutionTraceKind.traceColor(accent: Color): Color = when (this) {
    ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> SemanticError
    ExecutionTraceKind.COMPLETED, ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.STEP_COMPLETED -> SemanticSuccess
    ExecutionTraceKind.RECOVERING -> SemanticWarn
    else -> accent
}

private fun ExecutionTraceKind.icon() = when (this) {
    ExecutionTraceKind.PLANNING, ExecutionTraceKind.STEP_STARTED, ExecutionTraceKind.TOOL_STARTED -> Icons.Outlined.PlayArrow
    ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.STEP_COMPLETED, ExecutionTraceKind.COMPLETED -> Icons.Outlined.CheckCircle
    ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> Icons.Outlined.ErrorOutline
    ExecutionTraceKind.TOOL_CANCELLED, ExecutionTraceKind.CANCELLED -> Icons.Outlined.PauseCircleOutline
    ExecutionTraceKind.RECOVERING, ExecutionTraceKind.REFLECTING -> Icons.Outlined.HourglassEmpty
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

@Composable
private fun StatusDot(accent: Color, active: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(accent.copy(alpha = if (active) 1f else 0.75f), CircleShape)
    )
}
