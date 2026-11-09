package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentTrace

/** Part 5 §5: the states a run screen can be in. */
sealed interface AgentRunScreenState {
    data object Loading : AgentRunScreenState
    data class Running(val trace: AgentTrace) : AgentRunScreenState
    data class WaitingApproval(val trace: AgentTrace) : AgentRunScreenState
    data class Completed(val trace: AgentTrace) : AgentRunScreenState
    data class Failed(val trace: AgentTrace, val cancelled: Boolean) : AgentRunScreenState
    /** Deleted, pruned by retention, or an id that never existed. */
    data object Missing : AgentRunScreenState
    /** Run data could not be loaded and the device is offline. */
    data object Offline : AgentRunScreenState
}

private val CANCEL_MARKERS = listOf("cancel", "أُلغ", "ألغ", "stopped")

internal fun AgentTrace.looksCancelled(): Boolean =
    !success && !inProgress &&
        CANCEL_MARKERS.any { finalResult.contains(it, ignoreCase = true) } &&
        steps.none { it.error != null && !it.success }

/**
 * @param loaded whether the store has emitted at least once (distinguishes Loading from Missing)
 * @param waitingApproval true while the live approval gate holds this run
 */
fun deriveRunScreenState(
    runId: String,
    traces: List<AgentTrace>,
    loaded: Boolean,
    online: Boolean = true,
    waitingApproval: Boolean = false,
): AgentRunScreenState {
    val trace = traces.firstOrNull { it.id == runId || it.traceId == runId }
    return when {
        trace == null && !loaded -> AgentRunScreenState.Loading
        trace == null -> if (online) AgentRunScreenState.Missing else AgentRunScreenState.Offline
        trace.inProgress && waitingApproval -> AgentRunScreenState.WaitingApproval(trace)
        trace.inProgress -> AgentRunScreenState.Running(trace)
        trace.success -> AgentRunScreenState.Completed(trace)
        else -> AgentRunScreenState.Failed(trace, cancelled = trace.looksCancelled())
    }
}
