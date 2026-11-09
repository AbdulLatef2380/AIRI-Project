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
        requestedModel: String? = null,
        requestedProvider: String? = null,
        resolvedModel: String? = null,
        resolvedProvider: String? = null,
        fallbackReason: String? = null,
        estimatedOutputTokens: Int? = null,
        firstVisibleTokenLatencyMs: Long? = null,
        traceId: String? = null,
        /** Model calls of this execution (Part 5 §3.4); merged in as [AgentStepType.MODEL_TURN] steps. */
        modelTurns: List<ModelTurnRecord> = emptyList(),
    ): AgentTrace? {
        if (executionId.isBlank() || events.isEmpty()) return null

        val ordered = events.sortedBy { it.sequence }
        val terminal = ordered.lastOrNull {
                it.kind == ExecutionTraceKind.COMPLETED ||
                it.kind == ExecutionTraceKind.FAILED ||
                it.kind == ExecutionTraceKind.TIMED_OUT ||
                it.kind == ExecutionTraceKind.CANCELLED
        }
        // A trace without a terminal event is incomplete, never successful.
        // Success must be proven by an explicit COMPLETED event.
        // Every failed tool/step must also have a later recovery success. This
        // prevents a final COMPLETED event from masking an unrecovered failure.
        val success = terminal?.kind == ExecutionTraceKind.COMPLETED &&
            hasRecoveredFailures(ordered, terminal.sequence)

        val allSteps = ordered.mapNotNull(::stepFor)
        val kept = if (allSteps.size <= AgentTraceManager.MAX_STEPS) {
            allSteps
        } else {
            // Preserve context and the newest evidence where terminal failures,
            // recovery, and completion steps are recorded.
            listOf(allSteps.first()) + allSteps.takeLast(AgentTraceManager.MAX_STEPS - 1)
        }
        // Model turns are merged after the cap: they would otherwise crowd out the work steps the
        // cap exists to keep, and they have their own bound ([ModelTurnSteps.MAX_TURNS]).
        val turnSteps = modelTurns
            .sortedBy { it.turn }
            .take(ModelTurnSteps.MAX_TURNS)
            .map { ModelTurnSteps.toStep(it) { text -> PrivacyGuard.redactForTrace(text, AgentTraceManager.STEP_OUTPUT_CHARS) } }
        val merged = if (turnSteps.isEmpty()) kept else (kept + turnSteps).sortedBy { it.timestampMs }
        val steps = merged.mapIndexed { index, step -> step.copy(stepIndex = index) }
        // The gap marker belongs right after the first work step, wherever turns landed around it.
        val gapIndex = if (allSteps.size > kept.size) {
            kept.getOrNull(1)?.let { second -> merged.indexOfFirst { it === second } }?.takeIf { it >= 0 } ?: 1
        } else 0

        return AgentTrace(
            id = traceId?.takeIf { it.isNotBlank() } ?: executionId,
            traceId = traceId?.takeIf { it.isNotBlank() } ?: executionId,
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
            requestedModel = requestedModel?.takeIf { it.isNotBlank() },
            requestedProvider = requestedProvider?.takeIf { it.isNotBlank() },
            resolvedModel = resolvedModel?.takeIf { it.isNotBlank() },
            resolvedProvider = resolvedProvider?.takeIf { it.isNotBlank() },
            fallbackReason = fallbackReason?.let {
                PrivacyGuard.redactForTrace(it, AgentTraceManager.STEP_OUTPUT_CHARS)
            },
            estimatedOutputTokens = estimatedOutputTokens,
            firstVisibleTokenLatencyMs = firstVisibleTokenLatencyMs,
            truncated = allSteps.size > kept.size,
            droppedSteps = allSteps.size - kept.size,
            droppedAtIndex = gapIndex,
        )
    }

    /**
     * True only when every failure up to the terminal event was followed by an explicit
     * RECOVERING event and then a completed tool/step (or the terminal COMPLETED itself).
     * A success before the failure, or a success after it without RECOVERING, does not clear it:
     * the old rule let `tool ok, tool failed, COMPLETED` be reported as a successful run.
     */
    internal fun hasRecoveredFailures(
        events: List<ExecutionTraceEvent>,
        terminalSequence: Long,
    ): Boolean {
        var pendingFailures = 0
        var recoveryStarted = false
        events.filter { it.sequence <= terminalSequence }.forEach { event ->
            when (event.kind) {
                ExecutionTraceKind.TOOL_FAILED,
                ExecutionTraceKind.TOOL_CANCELLED,
                ExecutionTraceKind.APPROVAL_CANCELLED,
                ExecutionTraceKind.APPROVAL_TIMEOUT,
                ExecutionTraceKind.TIMED_OUT -> {
                    pendingFailures++
                    recoveryStarted = false
                }
                ExecutionTraceKind.RECOVERING -> recoveryStarted = true
                ExecutionTraceKind.TOOL_COMPLETED,
                ExecutionTraceKind.STEP_COMPLETED -> {
                    if (pendingFailures > 0 && recoveryStarted) pendingFailures--
                    recoveryStarted = false
                }
                ExecutionTraceKind.COMPLETED -> {
                    if (pendingFailures > 0 && recoveryStarted) pendingFailures--
                }
                else -> Unit
            }
        }
        return pendingFailures == 0
    }

    /** Only events that mark a finished unit of work become steps. */
    private fun stepFor(event: ExecutionTraceEvent): AgentStep? {
        val (type, ok) = when (event.kind) {
            ExecutionTraceKind.TOOL_COMPLETED -> AgentStepType.TOOL to true
            ExecutionTraceKind.TOOL_FAILED -> AgentStepType.TOOL to false
            ExecutionTraceKind.TOOL_CANCELLED -> AgentStepType.TOOL to false
            ExecutionTraceKind.STEP_COMPLETED -> AgentStepType.TASK_STEP to true
            ExecutionTraceKind.APPROVAL_CANCELLED -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.APPROVAL_TIMEOUT -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.TIMED_OUT -> AgentStepType.TASK_STEP to false
            else -> return null
        }
        val decisionType = when (event.machineTag) {
            "decision_intent" -> AgentStepType.INTENT
            "decision_plan" -> AgentStepType.PLAN
            "decision_verify" -> AgentStepType.VERIFY
            else -> null
        }
        // For tool events `actionId` is an opaque per-call id; the readable tool name is the part of
        // the summary after "Tool completed: " / "Tool failed: " / "Tool cancelled: ".
        val toolName = if (event.machineTag == "tool_call") toolNameFrom(event.summary) else null
        // A successful tool event carries only `detail` (source and result length), which the old
        // mapping dropped; show it instead of repeating the "Tool completed" summary.
        val shownOutput = if (ok && toolName != null) event.detail?.takeIf { it.isNotBlank() } ?: event.summary else event.summary
        return AgentStep(
            stepIndex = 0,
            type = decisionType ?: type,
            name = toolName ?: event.actionId ?: event.machineTag ?: event.kind.name.lowercase(),
            outputSummary = PrivacyGuard.redactForTrace(shownOutput, AgentTraceManager.STEP_OUTPUT_CHARS),
            success = ok,
            error = if (ok) null else PrivacyGuard.redactForTrace(
                event.detail ?: event.errorCode ?: event.summary,
                AgentTraceManager.STEP_OUTPUT_CHARS,
            ),
            durationMs = event.durationMs ?: 0L,
            parentId = event.parentId,
            timestampMs = event.timestampMs,
            inputParams = event.inputParams,
        )
    }

    /** Tool name from a tool lifecycle summary such as "Tool completed: write_file", or null. */
    internal fun toolNameFrom(summary: String): String? =
        summary.substringAfter(": ", "").trim().takeIf { it.isNotEmpty() && !it.contains(' ') }
}
