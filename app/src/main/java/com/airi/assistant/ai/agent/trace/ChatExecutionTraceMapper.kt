package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.execution.privacy.PrivacyGuard

/**
 * Converts the chat path's [ExecutionTraceEvent]s into an [AgentTrace] so the
 * agent-log screen shows real executions instead of an always-empty store.
 */
object ChatExecutionTraceMapper {

    fun toAgentTrace(
        executionId: String,
        sessionId: String,
        messageId: Long,
        userInput: String,
        events: List<ExecutionTraceEvent>,
        answerText: String,
        model: String? = null,
        provider: String? = null,
        estimatedOutputTokens: Int? = null,
        firstVisibleTokenLatencyMs: Long? = null,
    ): AgentTrace? {
        if (executionId.isBlank() || events.isEmpty()) return null

        val ordered = events.sortedBy { it.sequence }
        val terminal = ordered.lastOrNull {
            it.kind == ExecutionTraceKind.COMPLETED ||
                it.kind == ExecutionTraceKind.FAILED ||
                it.kind == ExecutionTraceKind.CANCELLED
        }
        // A trace without a terminal event is incomplete, never successful.
        // Success must be proven by an explicit COMPLETED event.
        val success = terminal?.kind == ExecutionTraceKind.COMPLETED

        val allSteps = ordered.mapNotNull(::stepFor)
        val kept = if (allSteps.size <= AgentTraceManager.MAX_STEPS) {
            allSteps
        } else {
            // Preserve context and the newest evidence where terminal failures,
            // recovery, and completion steps are recorded.
            listOf(allSteps.first()) + allSteps.takeLast(AgentTraceManager.MAX_STEPS - 1)
        }
        val steps = kept.mapIndexed { index, step -> step.copy(stepIndex = index) }

        return AgentTrace(
            id = executionId,
            originalInput = PrivacyGuard.redactForTrace(userInput, AgentTraceManager.INPUT_CHARS),
            steps = steps,
            finalResult = PrivacyGuard.redactForTrace(
                terminal?.takeIf { !success }?.summary?.takeIf { it.isNotBlank() } ?: answerText,
                AgentTraceManager.RESULT_CHARS,
            ),
            success = success,
            timestamp = ordered.first().timestampMs,
            executionId = executionId,
            messageId = messageId,
            sessionId = sessionId,
            source = AgentTrace.SOURCE_CHAT,
            model = model,
            provider = provider,
            estimatedOutputTokens = estimatedOutputTokens,
            firstVisibleTokenLatencyMs = firstVisibleTokenLatencyMs,
            truncated = allSteps.size > kept.size,
            droppedSteps = allSteps.size - kept.size,
        )
    }

    /** Only events that mark a finished unit of work become steps. */
    private fun stepFor(event: ExecutionTraceEvent): AgentStep? {
        val (type, ok) = when (event.kind) {
            ExecutionTraceKind.TOOL_COMPLETED -> AgentStepType.TOOL to true
            ExecutionTraceKind.TOOL_FAILED -> AgentStepType.TOOL to false
            ExecutionTraceKind.TOOL_CANCELLED -> AgentStepType.TOOL to false
            ExecutionTraceKind.STEP_COMPLETED -> AgentStepType.TASK_STEP to true
            ExecutionTraceKind.RECOVERING -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.APPROVAL_CANCELLED -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.APPROVAL_TIMEOUT -> AgentStepType.TASK_STEP to false
            else -> return null
        }
        return AgentStep(
            stepIndex = 0,
            type = type,
            name = event.actionId ?: event.machineTag ?: event.kind.name.lowercase(),
            outputSummary = PrivacyGuard.redactForTrace(event.summary, AgentTraceManager.STEP_OUTPUT_CHARS),
            success = ok,
            error = if (ok) null else PrivacyGuard.redactForTrace(
                event.detail ?: event.errorCode ?: event.summary,
                AgentTraceManager.STEP_OUTPUT_CHARS,
            ),
            durationMs = event.durationMs ?: 0L,
        )
    }
}
