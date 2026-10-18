package com.airi.assistant.ui.trace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
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

    val planningTaskLabel = stringResource(R.string.agent_trace_live_planning_task)
    val executionCompletedLabel = stringResource(R.string.agent_trace_kind_completed)
    val projection = remember(
        executionId,
        entries,
        activityEvents,
        planningTaskLabel,
        executionCompletedLabel,
    ) {
        UnifiedExecutionTraceProjector.projectProjection(
            executionId = executionId,
            traceEntries = entries,
            activityEvents = activityEvents,
            planningTaskLabel = planningTaskLabel,
            executionCompletedLabel = executionCompletedLabel,
        )
    }
    if (!isWorking && projection.events.isEmpty()) return

    AgentLifecycleTrackerPanel(
        projection = projection,
        isWorking = isWorking,
        modifier = modifier,
    )
}
