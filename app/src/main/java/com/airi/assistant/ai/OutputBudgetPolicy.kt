package com.airi.assistant.ai

import com.airi.assistant.ai.context.ContextBudget

/** Single source of truth for generation budgets across local and cloud backends. */
object OutputBudgetPolicy {
    fun requestedTokens(input: String, queryType: QueryType): Int = when (ResponseDepthPolicy.resolve(input, queryType)) {
        ResponseDepth.BRIEF -> 384
        ResponseDepth.STANDARD -> 1_024
        ResponseDepth.DETAILED -> when (queryType) {
            QueryType.ANALYTICAL -> 8_192
            QueryType.CREATIVE -> 6_144
            else -> 8_192
        }
    }

    fun effectiveTokens(
        input: String,
        queryType: QueryType,
        estimatedPromptTokens: Int,
        providerId: String,
        budget: ContextBudget,
    ): Int {
        val requested = requestedTokens(input, queryType)
        val local = providerId.isBlank() || providerId.equals("local", ignoreCase = true)
        return if (local) {
            ResponseOptimizer.localOutputTokenCap(
                nCtx = budget.nCtx,
                estimatedPromptTokens = estimatedPromptTokens,
                requestedMaxTokens = requested,
            )
        } else requested
    }
}
