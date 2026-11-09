package com.airi.assistant.ai

import com.airi.assistant.ai.context.ContextBudget

/** Single source of truth for generation budgets across local and cloud backends. */
object OutputBudgetPolicy {
    /**
     * A code task delivers its whole program inside one write_file call. Routed as ACTION it
     * would otherwise fall back to the 2,048-token STANDARD budget, and a call cut by the output
     * cap arrives with truncated or empty content. It keeps the long-form budget that the same
     * request had when it was routed as CREATIVE.
     */
    const val CODE_TASK_TOKENS = 10_240

    fun requestedTokens(input: String, queryType: QueryType): Int {
        if (CodingTaskDetector.isCodingTask(input)) return CODE_TASK_TOKENS
        return depthBudget(input, queryType)
    }

    private fun depthBudget(input: String, queryType: QueryType): Int = when (ResponseDepthPolicy.resolve(input, queryType)) {
        ResponseDepth.BRIEF -> 384
        ResponseDepth.STANDARD -> 2_048
        ResponseDepth.DETAILED -> when (queryType) {
            // Long-form answers and source files commonly span hundreds of
            // lines. Providers may still enforce a lower model-specific cap.
            QueryType.ANALYTICAL -> 12_288
            QueryType.CREATIVE -> 10_240
            // Routing describes tool/side-effect needs, not answer length.
            // Open-ended explanations can still be routed as SIMPLE.
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
