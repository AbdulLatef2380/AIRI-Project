package com.airi.assistant.ai.agent.trace

import com.airi.assistant.execution.privacy.PrivacyGuard

/** Redacts every persisted display field, including legacy producers that bypass the chat mapper. */
internal fun AgentTrace.sanitizeForTraceStorage(): AgentTrace = copy(
    originalInput = PrivacyGuard.redactForTrace(originalInput, AgentTraceManager.INPUT_CHARS),
    steps = steps.map { step ->
        step.copy(
            name = PrivacyGuard.redactForTrace(step.name, 80),
            inputParams = step.inputParams.mapValues { (key, value) ->
                PrivacyGuard.redactTraceField(key, value)
            },
            outputSummary = PrivacyGuard.redactForTrace(step.outputSummary, AgentTraceManager.STEP_OUTPUT_CHARS),
            error = step.error?.let {
                PrivacyGuard.redactForTrace(it, AgentTraceManager.STEP_OUTPUT_CHARS)
            },
            durationMs = step.durationMs.coerceAtLeast(0L),
            parentId = step.parentId?.let { PrivacyGuard.redactForTrace(it, 160) },
        )
    },
    finalResult = PrivacyGuard.redactForTrace(finalResult, AgentTraceManager.RESULT_CHARS),
    model = model?.takeIf { it.isNotBlank() }?.let { PrivacyGuard.redactForTrace(it, 80) },
    provider = provider?.takeIf { it.isNotBlank() }?.let { PrivacyGuard.redactForTrace(it, 80) },
    requestedModel = requestedModel?.takeIf { it.isNotBlank() }?.let { PrivacyGuard.redactForTrace(it, 80) },
    requestedProvider = requestedProvider?.takeIf { it.isNotBlank() }?.let { PrivacyGuard.redactForTrace(it, 80) },
    resolvedModel = resolvedModel?.takeIf { it.isNotBlank() }?.let { PrivacyGuard.redactForTrace(it, 80) },
    resolvedProvider = resolvedProvider?.takeIf { it.isNotBlank() }?.let { PrivacyGuard.redactForTrace(it, 80) },
    fallbackReason = fallbackReason?.let {
        PrivacyGuard.redactForTrace(it, AgentTraceManager.STEP_OUTPUT_CHARS)
    },
    estimatedOutputTokens = estimatedOutputTokens?.coerceAtLeast(0),
    firstVisibleTokenLatencyMs = firstVisibleTokenLatencyMs?.coerceAtLeast(0L),
    droppedSteps = droppedSteps.coerceAtLeast(0),
)
