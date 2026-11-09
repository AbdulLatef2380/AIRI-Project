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

        val ordered = events.sortedWith(compareBy<ExecutionTraceEvent> { it.sequence }.thenBy { it.eventId })
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
        val verificationRequired = ordered.any { it.kind == ExecutionTraceKind.VERIFICATION_STARTED }
        val verificationPassed = ordered.any {
            it.kind == ExecutionTraceKind.VERIFICATION_PASSED &&
                it.sequence <= (terminal?.sequence ?: Long.MAX_VALUE)
        }
        val success = terminal?.kind == ExecutionTraceKind.COMPLETED &&
            hasRecoveredFailures(ordered, terminal.sequence) &&
            (!verificationRequired || verificationPassed)

        val outputByActionId = ordered
            .filter { it.kind == ExecutionTraceKind.TOOL_OUTPUT && it.actionId != null }
            .associateBy { it.actionId!! }
        val rawSteps = ordered.mapNotNull { stepFor(it, outputByActionId) }
        // Request/start/output/completion is one logical action. Keep every source phase
        // in lifecycleKinds rather than creating duplicate rows for one action.
        val folded = linkedMapOf<String, AgentStep>()
        rawSteps.forEach { step ->
            val key = step.actionId?.let { "action:$it" } ?: "event:${step.eventId}"
            val previous = folded[key]
            folded[key] = if (previous == null) step else step.copy(
                stepIndex = previous.stepIndex,
                lifecycleKinds = (previous.lifecycleKinds + step.lifecycleKinds).distinct(),
                inputParams = if (step.inputParams.isNotEmpty()) step.inputParams else previous.inputParams,
                outputSummary = step.outputSummary.ifBlank { previous.outputSummary },
                error = if (step.traceStatus == com.airi.assistant.core.ExecutionTraceStatus.COMPLETED) {
                    null
                } else step.error ?: previous.error,
                parentId = step.parentId ?: previous.parentId,
                orphanedParent = previous.orphanedParent || step.orphanedParent,
            )
        }
        val knownActionIds = ordered.mapNotNull { it.actionId }.toSet()
        val allSteps = folded.values.map { step ->
            val orphaned = step.parentId != null && step.parentId !in knownActionIds
            step.copy(
                orphanedParent = orphaned,
                name = if (orphaned) "orphaned_parent:${step.name}" else step.name,
            )
        }
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
        // Model turns do not carry execution sequence; retain their timestamp interleaving with
        // work steps while the execution-only path above remains sequence/eventId ordered.
        val merged = if (turnSteps.isEmpty()) kept else (kept + turnSteps).sortedWith(
            compareBy<AgentStep> { it.timestampMs }.thenBy { it.eventId }
        )
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

    /** Every lifecycle event is retained; action phases are folded later by actionId. */
    private fun stepFor(
        event: ExecutionTraceEvent,
        outputByActionId: Map<String, ExecutionTraceEvent> = emptyMap(),
    ): AgentStep? {
        // The terminal execution record remains authoritative in AgentTrace metadata; keeping it
        // out of the work list avoids a duplicate "completed" row while all non-terminal lifecycle
        // evidence (including EXECUTION_STARTED) remains visible.
        if (event.actionId.isNullOrBlank() && event.kind == ExecutionTraceKind.COMPLETED) return null
        val (type, ok) = when (event.kind) {
            ExecutionTraceKind.EXECUTION_STARTED,
            ExecutionTraceKind.PLAN_CREATED,
            ExecutionTraceKind.REASONING_NOTE,
            ExecutionTraceKind.PLANNING,
            ExecutionTraceKind.STEP_STARTED,
            ExecutionTraceKind.STEP_COMPLETED -> AgentStepType.TASK_STEP to (event.status == com.airi.assistant.core.ExecutionTraceStatus.COMPLETED)
            ExecutionTraceKind.TOOL_REQUESTED,
            ExecutionTraceKind.TOOL_STARTED,
            ExecutionTraceKind.TOOL_OUTPUT,
            ExecutionTraceKind.TOOL_COMPLETED -> AgentStepType.TOOL to (event.status == com.airi.assistant.core.ExecutionTraceStatus.COMPLETED)
            ExecutionTraceKind.TOOL_FAILED -> AgentStepType.TOOL to false
            ExecutionTraceKind.TOOL_CANCELLED -> AgentStepType.TOOL to false
            ExecutionTraceKind.APPROVAL_CANCELLED -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.APPROVAL_TIMEOUT -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.TIMED_OUT -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.WAITING_FOR_APPROVAL,
            ExecutionTraceKind.RECOVERING,
            ExecutionTraceKind.CANCELLED,
            ExecutionTraceKind.FAILED -> AgentStepType.TASK_STEP to false
            ExecutionTraceKind.REFLECTING,
            ExecutionTraceKind.VERIFICATION_STARTED,
            ExecutionTraceKind.VERIFICATION_PASSED -> AgentStepType.VERIFY to (event.status == com.airi.assistant.core.ExecutionTraceStatus.COMPLETED)
            ExecutionTraceKind.VERIFICATION_FAILED -> AgentStepType.VERIFY to false
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
        val outputEvidence = outputByActionId[event.actionId]?.outputSummary
        val shownOutput = if (ok && toolName != null) {
            outputEvidence?.takeIf { it.isNotBlank() }
                ?: event.detail?.takeIf { it.isNotBlank() }
                ?: event.summary
        } else event.summary
        return AgentStep(
            stepIndex = 0,
            type = decisionType ?: type,
            name = toolName ?: event.actionId ?: event.machineTag ?: event.kind.name.lowercase(),
            outputSummary = PrivacyGuard.redactForTrace(shownOutput, AgentTraceManager.STEP_OUTPUT_CHARS),
            success = ok,
            error = if (event.status == com.airi.assistant.core.ExecutionTraceStatus.FAILED ||
                event.status == com.airi.assistant.core.ExecutionTraceStatus.CANCELLED
            ) {
                PrivacyGuard.redactForTrace(
                    event.detail ?: event.errorCode ?: event.summary,
                    AgentTraceManager.STEP_OUTPUT_CHARS,
                )
            } else null,
            durationMs = event.durationMs ?: 0L,
            parentId = event.parentId,
            timestampMs = event.timestampMs,
            inputParams = event.inputParams,
            eventId = event.eventId,
            actionId = event.actionId,
            traceStatus = event.status,
            lifecycleKinds = listOf(event.kind),
            sequence = event.sequence,
        )
    }

    /** Tool name from a tool lifecycle summary such as "Tool completed: write_file", or null. */
    internal fun toolNameFrom(summary: String): String? =
        summary.substringAfter(": ", "").trim().takeIf { it.isNotEmpty() && !it.contains(' ') }
}
