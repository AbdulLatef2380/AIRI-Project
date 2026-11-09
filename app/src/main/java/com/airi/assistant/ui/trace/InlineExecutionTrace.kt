package com.airi.assistant.ui.trace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.core.AgentEventBus
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.AgentLifecycleTrackerPanel

/** Renders one execution-owned, grouped timeline inline with the assistant turn. */
@Composable
fun InlineExecutionTrace(
    executionId: String,
    entries: List<ExecutionTraceEvent>,
    isWorking: Boolean,
    activityEvents: List<ActivityEvent> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (executionId.isBlank()) return

    val context = LocalContext.current
    val planningTaskLabel = stringResource(R.string.agent_trace_live_planning_task)
    val executionCompletedLabel = stringResource(R.string.agent_trace_kind_completed)
    val canonicalEvents = AgentEventBus.recent.collectAsState().value
        .filter { it.executionId == executionId }
    val projection = remember(
        executionId,
        entries,
        activityEvents,
        canonicalEvents,
        planningTaskLabel,
        executionCompletedLabel,
    ) {
        if (canonicalEvents.isNotEmpty()) {
            UnifiedExecutionTraceProjector.projectCanonical(
                executionId = executionId,
                events = canonicalEvents,
                planningTaskLabel = planningTaskLabel,
                executionCompletedLabel = executionCompletedLabel,
                titleResolver = { machineTag, fallback ->
                    localizedTraceTitle(context, machineTag, fallback)
                },
            )
        } else {
            UnifiedExecutionTraceProjector.projectProjection(
                executionId = executionId,
                traceEntries = entries,
                activityEvents = activityEvents,
                planningTaskLabel = planningTaskLabel,
                executionCompletedLabel = executionCompletedLabel,
                titleResolver = { machineTag, fallback ->
                    localizedTraceTitle(context, machineTag, fallback)
                },
            )
        }
    }
    // Never render an empty or admission-only panel for a direct response.
    // Once a user-visible activity is admitted, the same surface remains
    // visible through its terminal milestone.
    if (!projection.admitted || projection.events.isEmpty()) return

    AgentLifecycleTrackerPanel(
        projection = projection,
        isWorking = isWorking,
        modifier = modifier,
    )
}


/**
 * True when [InlineExecutionTrace] would render a panel for these events. Lets other screens
 * (the agent-log detail) decide whether to show the unified timeline or fall back to raw steps.
 * Admission depends only on event categories, so the labels are irrelevant here.
 */
fun executionTraceAdmitted(
    executionId: String,
    entries: List<ExecutionTraceEvent>,
    activityEvents: List<ActivityEvent> = emptyList(),
): Boolean = UnifiedExecutionTraceProjector.projectProjection(
    executionId = executionId,
    traceEntries = entries,
    activityEvents = activityEvents,
    planningTaskLabel = "",
    executionCompletedLabel = "",
).let { it.admitted && it.events.isNotEmpty() }

private fun localizedTraceTitle(context: Context, machineTag: String?, fallback: String): String {
    val tag = machineTag?.substringBefore(':')?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_]+")) }
        ?: return fallback
    val resourceId = context.resources.getIdentifier(
        "agent_trace_title_$tag",
        "string",
        context.packageName,
    )
    return resourceId.takeIf { it != 0 }?.let { id -> context.getString(id) } ?: fallback
}
