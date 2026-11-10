package com.airi.assistant.agent.subagent

import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.execution.privacy.PrivacyGuard

/**
 * Bridges the SubAgent event contract into the execution-owned Trace Gateway.
 *
 * SubAgent events are intentionally not UI events. This adapter owns the only
 * translation point for progress, delegation, bounded streaming output, terminal
 * completion, failure, and cancellation. Identity is copied from [context]; it
 * is never inferred from the currently active UI execution.
 */
class SubAgentTraceAdapter(
    private val context: SubAgentContext,
    private val agentId: String,
) {
    private val executionId = context.executionId
    private val parentId = context.parentId
    private val rootActionId = context.actionId.ifBlank { "subagent:$agentId" }
    private val activeActions = linkedMapOf<String, String>()
    private var partialText = StringBuilder()
    private var partialEmissions = 0
    private var nextActionSequence = 0L
    private var terminal = false

    @Synchronized
    fun accept(event: AgentEvent) {
        if (executionId.isBlank()) return
        if (terminal) return
        when (event) {
            is AgentEvent.Progress -> {
                ExecutionStatusBus.onSubAgentProgress(
                    executionId = executionId,
                    parentId = parentId,
                    actionId = rootActionId,
                    agentId = agentId,
                    stepName = event.stepName,
                    message = event.message,
                    percentComplete = event.percentComplete,
                )
            }
            is AgentEvent.ToolCall -> {
                val actionId = actionId("tool:${event.toolName}")
                activeActions[actionId] = event.toolName
                ExecutionStatusBus.onToolRequested(
                    executionId = executionId,
                    actionId = actionId,
                    toolName = event.toolName,
                    detail = "Sub-agent tool request",
                    inputParams = event.params,
                    parentId = parentId ?: rootActionId,
                )
                ExecutionStatusBus.onToolStarted(
                    executionId = executionId,
                    actionId = actionId,
                    toolName = event.toolName,
                    detail = "Sub-agent tool started",
                    inputParams = event.params,
                    parentId = parentId ?: rootActionId,
                )
            }
            is AgentEvent.Delegate -> {
                val actionId = actionId("delegate:${event.targetAgentId}")
                activeActions[actionId] = event.targetAgentId
                val safeTarget = event.targetAgentId.take(80)
                ExecutionStatusBus.onToolRequested(
                    executionId = executionId,
                    actionId = actionId,
                    toolName = "subagent_delegate",
                    detail = "Delegation requested",
                    inputParams = mapOf("targetAgentId" to safeTarget),
                    parentId = parentId ?: rootActionId,
                )
                ExecutionStatusBus.onToolStarted(
                    executionId = executionId,
                    actionId = actionId,
                    toolName = "subagent_delegate",
                    detail = "Delegation started",
                    inputParams = mapOf("targetAgentId" to safeTarget),
                    parentId = parentId ?: rootActionId,
                )
            }
            is AgentEvent.PartialResult -> {
                partialText.append(event.text)
                if (partialEmissions < MAX_PARTIAL_EMISSIONS &&
                    partialText.length - lastPublishedLength >= PARTIAL_CHUNK_CHARS
                ) {
                    publishPartial(force = event.isFinal)
                }
            }
            is AgentEvent.Complete -> {
                terminal = true
                if (partialText.isNotEmpty()) publishPartial(force = true)
                val safeOutput = PrivacyGuard.redactForTrace(event.result, MAX_OUTPUT_CHARS)
                ExecutionStatusBus.onSubAgentOutput(
                    executionId = executionId,
                    actionId = rootActionId,
                    outputSummary = safeOutput,
                    provenance = "subagent:$agentId",
                    parentId = parentId,
                )
                closeActiveActions(event.durationMs)
                ExecutionStatusBus.onSubAgentCompleted(
                    executionId = executionId,
                    actionId = rootActionId,
                    agentId = agentId,
                    durationMs = event.durationMs,
                    parentId = parentId,
                )
            }
            is AgentEvent.Failed -> {
                terminal = true
                closeActiveActions(0L, failed = true, errorCode = event.errorCode.ifBlank { "subagent_failed" })
                ExecutionStatusBus.onSubAgentFailed(
                    executionId = executionId,
                    actionId = rootActionId,
                    agentId = agentId,
                    reason = event.reason,
                    errorCode = event.errorCode.ifBlank { "subagent_failed" },
                    parentId = parentId,
                )
            }
        }
    }

    @Synchronized
    fun cancelled() {
        if (executionId.isBlank()) return
        if (terminal) return
        terminal = true
        closeActiveActions(0L, failed = false)
        ExecutionStatusBus.onSubAgentCancelled(
            executionId = executionId,
            actionId = rootActionId,
            agentId = agentId,
            parentId = parentId,
        )
    }

    /** Close an unfinished stream as a typed timeout, not as a normal cancellation. */
    @Synchronized
    fun timedOut() {
        if (executionId.isBlank() || terminal) return
        terminal = true
        closeActiveActions(0L, failed = true, errorCode = "subagent_timeout")
        ExecutionStatusBus.onSubAgentFailed(
            executionId = executionId,
            actionId = rootActionId,
            agentId = agentId,
            reason = "Sub-agent execution timed out",
            errorCode = "subagent_timeout",
            parentId = parentId,
        )
    }

    private fun actionId(suffix: String): String = "$rootActionId:$suffix:${nextActionSequence++}"

    private var lastPublishedLength = 0

    private fun publishPartial(force: Boolean = false) {
        if (!force && partialEmissions >= MAX_PARTIAL_EMISSIONS) return
        val summary = partialText.toString().takeLast(PARTIAL_CHUNK_CHARS)
        if (summary.isBlank()) return
        ExecutionStatusBus.onSubAgentOutput(
            executionId = executionId,
            actionId = rootActionId,
            outputSummary = PrivacyGuard.redactForTrace(summary, PARTIAL_CHUNK_CHARS),
            provenance = "subagent:$agentId:stream",
            parentId = parentId,
        )
        partialEmissions += 1
        lastPublishedLength = partialText.length
    }

    private fun closeActiveActions(durationMs: Long, failed: Boolean = false, errorCode: String? = null) {
        activeActions.toList().forEach { (actionId, toolName) ->
            if (failed) {
                ExecutionStatusBus.onToolFailed(executionId, actionId, toolName, durationMs, errorCode = errorCode)
            } else {
                ExecutionStatusBus.onToolCompleted(executionId, actionId, toolName, durationMs)
            }
        }
        activeActions.clear()
    }

    private companion object {
        const val PARTIAL_CHUNK_CHARS = 512
        const val MAX_PARTIAL_EMISSIONS = 4
        const val MAX_OUTPUT_CHARS = 2_000
    }
}
