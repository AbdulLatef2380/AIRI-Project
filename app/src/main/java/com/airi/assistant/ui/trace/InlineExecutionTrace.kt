package com.airi.assistant.ui.trace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.AgentLifecycleTrackerPanel
import com.airi.assistant.ui.activity.AgentTask

/**
 * Renders one normalized execution timeline inline with the assistant turn.
 *
 * Source reconciliation belongs to [UnifiedExecutionTraceProjector], not to the UI. This keeps
 * the Composable deterministic and prevents duplicate lifecycle cards when the same transition
 * arrives through both the structured trace and compatibility activity feed.
 */
@Composable
fun InlineExecutionTrace(
    executionId: String,
    entries: List<ExecutionTraceEvent>,
    isWorking: Boolean,
    activityEvents: List<ActivityEvent> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (executionId.isBlank()) return

    val planningTaskLabel = stringResource(R.string.agent_trace_live_planning_task)
    val executionCompletedLabel = stringResource(R.string.agent_trace_kind_completed)
    val projectedEvents = remember(
        executionId,
        entries,
        activityEvents,
        planningTaskLabel,
        executionCompletedLabel,
    ) {
        UnifiedExecutionTraceProjector.project(
            executionId = executionId,
            traceEntries = entries,
            activityEvents = activityEvents,
            planningTaskLabel = planningTaskLabel,
            executionCompletedLabel = executionCompletedLabel,
        )
    }
    if (!isWorking && projectedEvents.isEmpty()) return

    AgentLifecycleTrackerPanel(
        tasks = projectedEvents.map { event ->
            AgentTask(
                id = event.eventId,
                title = event.title,
                status = event.status,
                detailLog = event.detailLog,
            )
        },
        isWorking = isWorking,
        modifier = modifier,
    )
}
