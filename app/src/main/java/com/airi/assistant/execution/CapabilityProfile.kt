package com.airi.assistant.execution

import com.airi.assistant.ai.context.ContextBudget

/**
 * Declares what a runtime backend can do.
 *
 * Every backend ([RuntimeBackend]) exposes a [CapabilityProfile].
 * The [RuntimeRouter] performs capability matching between what the
 * [ExecutionRequest] requires and what each candidate backend supports —
 * routing never sends a request to a backend that cannot fulfill it.
 *
 * All boolean fields default to false so partial declarations are safe.
 *
 * @param supportsStreaming         Emits tokens one-by-one (vs. batch).
 * @param supportsToolCalling       Can invoke JSON-described tool calls.
 * @param supportsVision            Accepts image / multimodal input.
 * @param supportsLongContext       Context window larger than 8 192 tokens.
 * @param supportsFastReasoning     Sub-second first-token latency.
 * @param supportsOffline           Works without a network connection.
 * @param supportsAccessibilityActions   Can execute on-device UI actions.
 * @param supportsVoiceRealtime     Bidirectional voice streaming support.
 * @param supportsStructuredOutput  Returns guaranteed JSON / schema output.
 * @param maxContextTokens          Maximum input + output token budget.
 * @param estimatedFirstTokenMs     Typical first-token latency hint (ms).
 *                                  Used by the router for latency-sensitive
 *                                  routing decisions.
 */
data class CapabilityProfile(
    val supportsStreaming:            Boolean = false,
    val supportsToolCalling:          Boolean = false,
    val supportsVision:               Boolean = false,
    val supportsLongContext:          Boolean = false,
    val supportsFastReasoning:        Boolean = false,
    val supportsOffline:              Boolean = false,
    val supportsAccessibilityActions: Boolean = false,
    val supportsVoiceRealtime:        Boolean = false,
    val supportsStructuredOutput:     Boolean = false,
    val maxContextTokens:             Int     = 2048,
    val estimatedFirstTokenMs:        Int     = 3000
) {

    /**
     * Returns true if this profile satisfies all capabilities required by
     * [request]. Missing capabilities on the backend cause routing to skip it.
     *
     * Capabilities that are not required (false in the request) are always
     * satisfied — the router only rejects backends that lack what is *needed*.
     */
    fun satisfies(request: ExecutionRequest): Boolean {
        if (request.requiresStreaming      && !supportsStreaming)      return false
        if (request.requiresVision         && !supportsVision)         return false
        if (request.requiresToolCalling    && !supportsToolCalling)    return false
        if (request.requiresLongContext    && !supportsLongContext)     return false
        if (request.requiresOffline        && !supportsOffline)        return false
        if (request.requiresStructuredOutput && !supportsStructuredOutput) return false
        return true
    }

    companion object {
        /**
         * Conservative static local profile used before a model is loaded.
         * SPRINT 1 migration note: maxContextTokens was hardcoded to 4096 here.
         * After model load, callers should use [forLocalModel] instead, which
         * derives all values from [ContextBudget] and reflects the actual
         * runtime nCtx from LlamaNative.getNCtx().
         */
        val LOCAL_CPU = CapabilityProfile(
            supportsStreaming            = true,
            supportsToolCalling          = true,
            supportsVision               = false,  // set true after mmproj load
            supportsLongContext          = false,   // will be true for 8K+ models via forLocalModel
            supportsFastReasoning        = false,
            supportsOffline              = true,
            supportsAccessibilityActions = true,
            supportsVoiceRealtime        = false,
            supportsStructuredOutput     = true,
            maxContextTokens             = 1536,   // conservative; replaced by forLocalModel after load
            estimatedFirstTokenMs        = 4000
        )

        /**
         * SPRINT 1: Build a live CapabilityProfile for the local backend
         * from the active [ContextBudget].
         *
         * This is the authoritative capability declaration after a model is
         * loaded. It replaces the hardcoded LOCAL_CPU constant for routing
         * decisions so that:
         *   - maxContextTokens reflects the actual nCtx from getNCtx()
         *   - supportsLongContext is true for 8K+ models
         *   - Vision is preserved from the caller (still model-dependent)
         *
         * @param budget    Active ContextBudget from LlamaManager.contextBudget.
         * @param hasVision True when an mmproj projector is loaded.
         */
        fun forLocalModel(
            budget:    ContextBudget,
            hasVision: Boolean = false
        ): CapabilityProfile = CapabilityProfile(
            supportsStreaming            = true,
            supportsToolCalling          = true,
            supportsVision               = hasVision,
            supportsLongContext          = budget.supportsLongContext,
            supportsFastReasoning        = false,
            supportsOffline              = true,
            supportsAccessibilityActions = true,
            supportsVoiceRealtime        = false,
            supportsStructuredOutput     = true,
            maxContextTokens             = budget.nCtx,
            estimatedFirstTokenMs        = 4000
        )

        /** Cloud provider with streaming (e.g. OpenAI gpt-4o). */
        val CLOUD_STREAMING = CapabilityProfile(
            supportsStreaming            = true,
            supportsToolCalling          = true,
            supportsVision               = true,
            supportsLongContext          = true,
            supportsFastReasoning        = true,
            supportsOffline              = false,
            supportsAccessibilityActions = false,
            supportsVoiceRealtime        = false,
            supportsStructuredOutput     = true,
            maxContextTokens             = 128_000,
            estimatedFirstTokenMs        = 800
        )
    }
}
