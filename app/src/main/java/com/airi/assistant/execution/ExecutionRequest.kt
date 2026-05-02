package com.airi.assistant.execution

import com.airi.assistant.ai.QueryType

/**
 * Unified request object passed through the execution layer.
 *
 * Carries everything the [RuntimeRouter] and backends need to make routing
 * and execution decisions — without pulling in ViewModel or UI dependencies.
 *
 * Fields are immutable; construct via [ExecutionRequest] or copy().
 *
 * @param prompt                    The user-facing input (already assembled
 *                                  with any context injection the VM did).
 * @param systemPrompt              System instruction prefix.
 * @param maxTokens                 Token budget for the response.
 * @param temperature               Sampling temperature (0..1).
 * @param queryType                 Classified intent ([QueryType]).
 * @param requiresStreaming          True for all interactive chat turns.
 * @param requiresVision            True when an image attachment is present.
 * @param requiresToolCalling       True when the agent pipeline needs tools.
 * @param requiresLongContext       True when the effective prompt exceeds
 *                                  8 192 tokens (estimated).
 * @param requiresOffline           True when device has no network and caller
 *                                  must not attempt cloud routing.
 * @param requiresStructuredOutput  True when the pipeline expects JSON output.
 * @param estimatedPromptTokens     Approximate prompt token count (chars / 4).
 *                                  Used by the router for context-limit checks.
 * @param sessionTag                Optional session identifier for log tracing.
 */
data class ExecutionRequest(
    val prompt:                  String,
    val systemPrompt:            String     = "",
    val maxTokens:               Int        = 512,
    val temperature:             Float      = 0.8f,
    val queryType:               QueryType  = QueryType.UNKNOWN,
    val requiresStreaming:       Boolean    = true,
    val requiresVision:          Boolean    = false,
    val requiresToolCalling:     Boolean    = false,
    val requiresLongContext:     Boolean    = false,
    val requiresOffline:         Boolean    = false,
    val requiresStructuredOutput: Boolean   = false,
    val estimatedPromptTokens:   Int        = 0,
    val sessionTag:              String     = ""
) {
    /**
     * Estimated total token demand (prompt + requested response).
     * The router uses this to prefer backends with adequate context windows.
     */
    val estimatedTotalTokens: Int
        get() = estimatedPromptTokens + maxTokens

    /**
     * Minimum capability requirements derived from this request.
     * The router calls [CapabilityProfile.satisfies] against this request
     * object — this helper shows what the routing check will verify.
     */
    val requirementSummary: String
        get() = buildString {
            append("streaming=${requiresStreaming}")
            if (requiresVision)          append(" vision=true")
            if (requiresToolCalling)     append(" tools=true")
            if (requiresLongContext)     append(" longCtx=true")
            if (requiresOffline)         append(" offline=true")
            if (requiresStructuredOutput) append(" structured=true")
        }
}
