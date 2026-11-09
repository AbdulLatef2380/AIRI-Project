package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Builds a trace snapshot from the events of a possibly still-running execution.
 * Pure function so it can be unit-tested without Android.
 */
object LiveTraceProjector {

    private val TERMINAL = setOf(
        ExecutionTraceKind.COMPLETED,
        ExecutionTraceKind.FAILED,
        ExecutionTraceKind.TIMED_OUT,
        ExecutionTraceKind.CANCELLED,
    )

    /** Same identity the chat path uses for its final record, so the final one replaces this one. */
    fun traceIdFor(executionId: String): String = "trace-$executionId"

    fun project(
        events: List<ExecutionTraceEvent>,
        goal: String,
        sessionId: String,
        modelTurns: List<ModelTurnRecord> = emptyList(),
    ): AgentTrace? {
        val ordered = events.sortedBy { it.sequence }
        val executionId = ordered.firstOrNull()?.executionId?.takeIf { it.isNotBlank() } ?: return null
        val input = goal.ifBlank { ordered.first().summary }
        val mapped = ChatExecutionTraceMapper.toAgentTrace(
            executionId = executionId,
            sessionId = sessionId.ifBlank { ordered.first().sessionId },
            messageId = 0L,
            userInput = input,
            events = ordered,
            answerText = "",
            traceId = traceIdFor(executionId),
            modelTurns = modelTurns,
        ) ?: return null
        val finished = ordered.any { it.kind in TERMINAL }
        return mapped.copy(messageId = null, inProgress = !finished)
    }
}

/**
 * Feeds [AgentTraceManager] while an execution is running, so the agent-log screen shows the run
 * as it happens instead of only after the answer is saved. The final record written by the chat
 * path uses the same trace id and replaces what this bridge published.
 *
 * A run with no recorded step yet (a plain chat reply) is not shown, matching the chat path's rule
 * that such replies are not agent runs.
 */
class LiveTraceBridge(
    private val events: Flow<List<ExecutionTraceEvent>>,
    /** Returns (goal, sessionId) for an execution id, or null when unknown. */
    private val goalFor: (String) -> Pair<String, String>?,
    private val manager: AgentTraceManager,
    /** Model calls recorded so far for an execution id (Part 5 §3.4). */
    private val turnsFor: (String) -> List<ModelTurnRecord> = { emptyList() },
    /** Emits when a model turn is recorded, so its row shows without waiting for the next trace event. */
    private val turnTicks: Flow<Long> = kotlinx.coroutines.flow.flowOf(0L),
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        kotlinx.coroutines.flow.combine(events, turnTicks) { list, _ -> list }
            .collect { list -> runCatching { handle(list) } }
    }

    internal fun handle(list: List<ExecutionTraceEvent>) {
        // The shared status stream can contain multiple admitted runs. Project each owner
        // independently so concurrent executions never mix and none is silently dropped.
        list.filter { it.executionId.isNotBlank() }
            .groupBy { it.executionId }
            .forEach { (executionId, scopedEvents) ->
                val (goal, session) = goalFor(executionId) ?: ("" to scopedEvents.first().sessionId)
                val trace = LiveTraceProjector.project(scopedEvents, goal, session, turnsFor(executionId)) ?: return@forEach
                // Model turns alone do not make an agent run: a plain reply must stay out of the log.
                if (trace.steps.none { it.type != AgentStepType.MODEL_TURN }) {
                    manager.removeLiveTrace(trace.id)
                } else {
                    manager.publishLive(trace)
                }
            }
    }
}
