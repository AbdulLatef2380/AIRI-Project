package com.airi.assistant.ui.trace

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn

/**
 * Manus-style work card embedded in the assistant turn.
 *
 * The card is part of the reply rather than a transient overlay. It opens
 * while execution is running, remains in the conversation after completion,
 * and can be collapsed without losing the execution history. Only sanitized
 * runtime summaries from the execution and activity buses reach this UI;
 * private model reasoning and raw tool arguments are never rendered.
 */
@Composable
fun InlineExecutionTrace(
    executionId: String,
    entries: List<ExecutionTraceEvent>,
    isWorking: Boolean,
    activityEvents: List<ActivityEvent> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (executionId.isBlank() || (entries.isEmpty() && activityEvents.isEmpty())) return

    // Runtime services publish concrete actions (terminal, connector, skill,
    // sandbox, and artifact work) through AgentActivityBus. Merge them with
    // the lifecycle trace only for this assistant turn. The plan panel uses a
    // different state model and never consumes these rows.
    val liveRows = remember(entries, activityEvents, executionId) {
        (entries.map { it.toLiveRow() } + activityEvents
            .filter { it.executionId == executionId }
            .map { it.toLiveRow() })
            .sortedBy { it.timestampMs }
    }
    if (liveRows.isEmpty()) return

    var expanded by rememberSaveable(executionId) { mutableStateOf(true) }
    val lastKind = entries.lastOrNull()?.kind
    val status = when {
        isWorking -> TraceStatus.RUNNING
        lastKind == ExecutionTraceKind.FAILED || lastKind == ExecutionTraceKind.TOOL_FAILED -> TraceStatus.FAILED
        lastKind == ExecutionTraceKind.CANCELLED || lastKind == ExecutionTraceKind.TOOL_CANCELLED -> TraceStatus.CANCELLED
        else -> TraceStatus.COMPLETED
    }
    val accent = when (status) {
        TraceStatus.RUNNING -> CosmicAccent
        TraceStatus.FAILED -> SemanticError
        TraceStatus.CANCELLED -> SemanticWarn
        TraceStatus.COMPLETED -> SemanticSuccess
    }
    val title = stringResource(R.string.agent_plan_trace_title)
    val statusLabel = when (status) {
        TraceStatus.RUNNING -> stringResource(R.string.agent_plan_stage_executing)
        TraceStatus.FAILED -> stringResource(R.string.agent_plan_stage_failed)
        TraceStatus.CANCELLED -> stringResource(R.string.agent_plan_stage_cancelled)
        TraceStatus.COMPLETED -> stringResource(R.string.agent_plan_stage_completed)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)
            .animateContentSize()
            .semantics {
                paneTitle = title
                contentDescription = "$title · $statusLabel · ${liveRows.size}"
            },
        shape = RoundedCornerShape(14.dp),
        color = AiriTheme.surfaceVariant.copy(alpha = 0.58f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(accent = accent, active = isWorking)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = AiriTheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = statusLabel,
                        color = accent,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = liveRows.size.toString(),
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = AiriTheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    liveRows.forEachIndexed { index, row ->
                        InlineTraceEventRow(
                            row = row,
                            accent = accent,
                            drawConnector = index < liveRows.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineTraceEventRow(
    row: LiveTraceRow,
    accent: Color,
    drawConnector: Boolean,
) {
    val color = row.color ?: accent
    val description = row.accessibilityLabel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(row.icon, contentDescription = null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
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
        Column(Modifier.weight(1f).padding(top = 1.dp)) {
            Text(
                text = row.summary,
                color = AiriTheme.onSurface.copy(alpha = 0.9f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    color = AiriTheme.onSurfaceVariant.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row.durationMs?.takeIf { it >= 0L }?.let {
            Text(
                text = stringResource(R.string.agent_plan_trace_duration_ms, it),
                color = AiriTheme.onSurfaceVariant.copy(alpha = 0.66f),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private data class LiveTraceRow(
    val timestampMs: Long,
    val summary: String,
    val detail: String?,
    val durationMs: Long?,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color?,
    val accessibilityLabel: String,
)

private fun ExecutionTraceEvent.toLiveRow() = LiveTraceRow(
    timestampMs = timestampMs,
    summary = summary,
    detail = detail,
    durationMs = durationMs,
    icon = kind.icon(),
    color = kind.traceColor(null),
    accessibilityLabel = "#${sequence} ${kind.name}: $summary" +
        (errorCode?.let { "; code=$it" } ?: ""),
)

private fun ActivityEvent.toLiveRow() = LiveTraceRow(
    timestampMs = timestampMs,
    summary = message,
    detail = detail,
    durationMs = null,
    icon = when (severity) {
        ActivitySeverity.ERROR -> Icons.Outlined.ErrorOutline
        ActivitySeverity.WARN -> Icons.Outlined.HourglassEmpty
        ActivitySeverity.INFO -> Icons.Outlined.PlayArrow
    },
    color = when (severity) {
        ActivitySeverity.ERROR -> SemanticError
        ActivitySeverity.WARN -> SemanticWarn
        ActivitySeverity.INFO -> CosmicAccent
    },
    accessibilityLabel = "${category.label}: $message",
)

private enum class TraceStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

private fun ExecutionTraceKind.traceColor(accent: Color?): Color = when (this) {
    ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> SemanticError
    ExecutionTraceKind.COMPLETED, ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.STEP_COMPLETED -> SemanticSuccess
    ExecutionTraceKind.RECOVERING -> SemanticWarn
    else -> accent ?: CosmicAccent
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
            .clip(CircleShape)
            .background(accent.copy(alpha = if (active) 1f else 0.75f))
    )
}
