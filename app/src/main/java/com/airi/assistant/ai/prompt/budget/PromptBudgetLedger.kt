package com.airi.assistant.ai.prompt.budget

import android.util.Log
import com.airi.assistant.ai.context.ContextBudget

/**
 * PromptBudgetLedger — shared token allocator for all prompt contributors.
 *
 * SPRINT 2: Prevents prompt overflow permanently.
 *
 * ## Problem it solves
 * Before Sprint 2, every contributor appended content to the system prompt
 * independently, with each using its own hardcoded character/token cap:
 *
 *   • PromptService.MAX_RAG_CHARS     = 2400 chars  (~600 tok) — hardcoded
 *   • PromptService.MAX_SUMMARY_CHARS = 1600 chars  (~400 tok) — hardcoded
 *   • DynamicPromptEngine.DEFAULT_MAX_RAG_TOKENS = 512         — hardcoded
 *   • AgentLoop.buildToolBlock() — no budget check at all
 *   • SkillRegistry.buildSkillDescriptionBlock() — no budget check at all
 *
 * None of these were coordinated. If all contributors injected at once on a
 * 1536-token context, the prompt could easily exceed nCtx before the history
 * or generation reserve was even considered.
 *
 * ## How it works
 * 1. [forBudget] mints a ledger from a [ContextBudget] and pre-reserves the
 *    guaranteed fixed slots (system overhead, generation reserve).
 * 2. Each contributor calls [claim] before appending content. The ledger
 *    returns the granted tokens (≤ requested, ≥ 0).
 * 3. [tokensToChars] converts a granted token count to a safe character limit.
 * 4. On budget exhaustion, claim returns 0 → contributor omits its content.
 *    Overflow is architecturally impossible.
 *
 * ## Contributor priority (pre-allocated first = highest priority)
 *   SYSTEM            — persona + template tags + user fragment (always reserved)
 *   GENERATION        — output headroom (always reserved)
 *   SUMMARY           — compressed history summary (high priority)
 *   RAG               — semantic memory retrieval (high priority)
 *   SKILLS            — skill description block (medium priority)
 *   TOOLS             — tool schema block (medium priority)
 *   EXTRA_CONTEXT     — screen / accessibility context (low priority)
 *   HISTORY           — conversation history turns (remaining budget)
 *
 * ## Scaling (5 / 20 / 50 skills, BALANCED 1536-token context)
 *   Available after fixed overhead = 920 tok
 *   RAG pre-allocated = 92 tok, Summary = 46 tok → 782 remaining
 *   Per-skill description ≈ 40 tok
 *
 *   5  skills: claim(SKILLS, 200)  → granted 200  → remaining 582 for tools+history
 *   20 skills: claim(SKILLS, 800)  → granted 782  → remaining 0   for tools+history
 *                                                    (tools omitted; no overflow)
 *   50 skills: claim(SKILLS, 2000) → granted 782  → remaining 0   for tools+history
 *                                                    (tools omitted; no overflow)
 *
 *   On a 32K model (availableForContent ≈ 30K):
 *   5  skills: granted 200  → 29.8K for tools+history
 *   20 skills: granted 800  → 29.2K for tools+history
 *   50 skills: granted 2000 → 28.0K for tools+history — all fit, no truncation
 *
 * ## Single-source skill architecture
 * Target flow:
 *   SkillRegistry
 *     └─→ buildSkillDescriptionBlock()  (formats all descriptions)
 *           └─→ PromptBudgetLedger.claim(SKILLS, estimatedTokens)
 *                 └─→ DynamicPromptEngine.build() injects trimmed block
 *
 * AgentLoop.buildToolBlock() claims from TOOLS slot. Both are coordinated
 * through the same ledger, preventing double-counting.
 */
class PromptBudgetLedger private constructor(val budget: ContextBudget) {

    private val allocations = mutableMapOf<Contributor, Int>()
    private var totalAllocated: Int = 0

    /** Remaining unallocated tokens in the context window. */
    val remaining: Int get() = budget.nCtx - totalAllocated

    /** How many tokens have been claimed in total. */
    val totalClaimed: Int get() = totalAllocated

    /**
     * Named contributors in priority order.
     * Lower ordinal = higher priority = allocated first.
     */
    enum class Contributor(val displayName: String) {
        SYSTEM("System/Persona"),
        GENERATION("Generation Reserve"),
        SUMMARY("Conversation Summary"),
        RAG("RAG Memory"),
        SKILLS("Skill Descriptions"),
        TOOLS("Tool Schemas"),
        EXTRA_CONTEXT("Extra Context"),
        HISTORY("Conversation History")
    }

    /**
     * Claim up to [requested] tokens for [contributor].
     *
     * @param contributor  The subsystem claiming budget.
     * @param requested    Tokens requested (based on estimated content size).
     * @return             Actual tokens granted. May be less than [requested]
     *                     if the budget is tight. Never negative.
     *
     * When the return value is 0, the contributor MUST omit its content.
     * Never append content whose token count exceeds the granted amount.
     */
    fun claim(contributor: Contributor, requested: Int): Int {
        val granted = requested.coerceAtMost(remaining).coerceAtLeast(0)
        allocations[contributor] = (allocations[contributor] ?: 0) + granted
        totalAllocated += granted
        if (granted < requested) {
            Log.w(TAG,
                "AIRI_PROOF BUDGET_PARTIAL contributor=${contributor.name} " +
                "requested=$requested granted=$granted remaining=$remaining nCtx=${budget.nCtx}")
        } else {
            Log.d(TAG,
                "BUDGET_CLAIM contributor=${contributor.name} " +
                "granted=$granted remaining=$remaining")
        }
        return granted
    }

    /** How many tokens have been allocated to [contributor] so far. */
    fun allocated(contributor: Contributor): Int = allocations[contributor] ?: 0

    /**
     * Convert a granted token count to a safe maximum character count.
     * Uses the same 4-chars-per-token ratio as DynamicPromptEngine and
     * PromptCompressor to ensure consistency.
     */
    fun tokensToChars(tokens: Int): Int = tokens * CHARS_PER_TOKEN

    /**
     * Trim [text] to fit within [grantedTokens].
     * Returns the trimmed string with a truncation marker if shortened.
     */
    fun trimToGranted(text: String, grantedTokens: Int): String {
        if (grantedTokens <= 0) return ""
        val maxChars = tokensToChars(grantedTokens)
        return if (text.length <= maxChars) text
               else text.take(maxChars) + "\n[...trimmed by PromptBudgetLedger]"
    }

    /**
     * Human-readable allocation report for AIRI_PROOF logging.
     */
    fun report(): String = buildString {
        appendLine("PromptBudgetLedger nCtx=${budget.nCtx} claimed=$totalAllocated remaining=$remaining")
        for (c in Contributor.entries) {
            val a = allocations[c] ?: 0
            if (a > 0) appendLine("  ${c.displayName}: $a tok")
        }
        if (remaining > 0) appendLine("  [available]: $remaining tok")
    }

    companion object {
        private const val TAG            = "AIRI_PromptBudget"
        private const val CHARS_PER_TOKEN = 4

        /**
         * Mint a ledger from a [ContextBudget] and pre-allocate the fixed slots.
         *
         * After this call:
         *   - SYSTEM slot holds: systemOverhead + templateOverhead + userFragmentReserve
         *   - GENERATION slot holds: generationReserve
         *   - SUMMARY slot holds: budget.summaryTokens  (dynamic, scales with nCtx)
         *   - RAG slot holds: budget.ragTokens           (dynamic, scales with nCtx)
         *
         * Remaining budget is available for SKILLS, TOOLS, EXTRA_CONTEXT, HISTORY.
         *
         * @param budget  The active ContextBudget from LlamaManager.contextBudget.
         * @return        A pre-populated ledger ready for contributor claims.
         */
        fun forBudget(budget: ContextBudget): PromptBudgetLedger {
            val ledger = PromptBudgetLedger(budget)
            val fixedSystem = budget.systemOverhead + budget.templateOverhead + budget.userFragmentReserve
            ledger.claim(Contributor.SYSTEM, fixedSystem)
            ledger.claim(Contributor.GENERATION, budget.generationReserve)
            ledger.claim(Contributor.SUMMARY, budget.summaryTokens)
            ledger.claim(Contributor.RAG, budget.ragTokens)
            Log.i(TAG,
                "AIRI_PROOF BUDGET_INIT nCtx=${budget.nCtx} " +
                "system=$fixedSystem gen=${budget.generationReserve} " +
                "summary=${budget.summaryTokens} rag=${budget.ragTokens} " +
                "remaining=${ledger.remaining}")
            return ledger
        }

        /**
         * Estimate the token count of [text] using the 4-chars-per-token heuristic.
         * Consistent with DynamicPromptEngine.estimateTokens().
         */
        fun estimateTokens(text: String): Int =
            if (text.isBlank()) 0 else (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }
}
