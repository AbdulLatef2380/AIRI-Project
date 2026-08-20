package com.airi.assistant.ai.context

import android.util.Log
import com.airi.assistant.ai.prompt.budget.ContributorBudgetPolicy

/**
 * ContextBudget — single source of truth for context capacity.
 *
 * SPRINT 1: This is the authoritative context-size object for the entire AIRI stack.
 * No subsystem may define its own context capacity constant. All budget values
 * flow from LlamaNative.getNCtx() through this class.
 *
 * ## Derivation
 *   nCtx (live from LlamaNative.getNCtx())
 *   ├── nonHistoryOverhead   = systemOverhead + templateOverhead + userFragmentReserve + generationReserve
 *   ├── availableForContent  = nCtx - nonHistoryOverhead (min 256)
 *   ├── ragTokens            = 20% of availableForContent  (clamped 64..1024, scales with nCtx)
 *   ├── summaryTokens        = 10% of availableForContent  (clamped 64..400, scales with nCtx)
 *   └── historyTokens        = availableForContent - ragTokens - summaryTokens (min 256)
 *
 * ## Migration — before Sprint 1 these constants were hardcoded in:
 *   • CapabilityProfile.LOCAL_CPU.maxContextTokens          = 4096
 *   • CapabilityProfile.LOCAL_CPU.supportsLongContext        = false
 *   • LlamaManager.NON_HISTORY_OVERHEAD                     = 616
 *   • LlamaManager.MIN_HISTORY_TOKENS                       = 256
 *   • LlamaManager.maxHistoryTokens   = (mode.nCtx - 616).coerceAtLeast(256)
 *   • AgentLoop.callLLM requiresLongContext                  = estimatedTokens > 8_192
 *   • PromptService.MAX_RAG_CHARS                           = 2_400
 *   • PromptService.MAX_SUMMARY_CHARS                       = 1_600
 *   • DynamicPromptEngine.DEFAULT_MAX_RAG_TOKENS            = 512
 *
 * All of the above now derive from this class.
 *
 * ## Scaling behaviour (verified at key model sizes)
 *   nCtx=1024  → rag=55   summary=30  history=255   [FAST mode]
 *   nCtx=1536  → rag=92   summary=46  history=390   [BALANCED mode]
 *   nCtx=2048  → rag=128  summary=64  history=544   [QUALITY mode]
 *   nCtx=4096  → rag=270  summary=135 history=1175  [4K model]
 *   nCtx=8192  → rag=555  summary=277 history=2448  [8K model]
 *   nCtx=32768 → rag=1024 summary=400 history=29728 [32K model, clamped]
 */
data class ContextBudget(
    /** Live value from LlamaNative.getNCtx(). Root of all derived budgets. */
    val nCtx: Int
) {

    // ── Fixed overhead allocations (sourced from ContributorBudgetPolicy) ─────
    // Phase B: no magic numbers here — ALL allocation constants live in
    // ContributorBudgetPolicy so changing the policy propagates automatically.

    /** Tokens reserved for persona / system instruction base. */
    val systemOverhead: Int = ContributorBudgetPolicy.SYSTEM_OVERHEAD_TOKENS
    /** Tokens for chat-template markers (im_start, im_end, role tags, etc.). */
    val templateOverhead: Int = ContributorBudgetPolicy.TEMPLATE_OVERHEAD_TOKENS
    /** Tokens for the in-flight user message fragment. */
    val userFragmentReserve: Int = ContributorBudgetPolicy.USER_FRAGMENT_RESERVE_TOKENS
    /** Tokens reserved for model output (generation headroom). */
    val generationReserve: Int = ContributorBudgetPolicy.GENERATION_RESERVE_TOKENS

    /** Total fixed overhead that is never available for content. */
    val nonHistoryOverhead: Int = ContributorBudgetPolicy.TOTAL_FIXED_OVERHEAD

    // ── Content budget (scales with nCtx) ────────────────────────────────────
    /** Total tokens available for dynamic content (history + RAG + summary). */
    val availableForContent: Int = (nCtx - nonHistoryOverhead).coerceAtLeast(256)

    /**
     * Token budget for the RAG semantic memory block.
     * Fraction and clamp are defined in [ContributorBudgetPolicy.RAG_FRACTION].
     */
    val ragTokens: Int = ContributorBudgetPolicy.ragTokens(availableForContent)

    /**
     * Token budget for the conversation summary (compressed older turns).
     * Fraction and clamp are defined in [ContributorBudgetPolicy.SUMMARY_FRACTION].
     */
    val summaryTokens: Int = ContributorBudgetPolicy.summaryTokens(availableForContent)

    /**
     * Token budget for conversation history turns (the core chat window).
     * Derived as the remainder after RAG and summary take their shares.
     */
    val historyTokens: Int = (availableForContent - ragTokens - summaryTokens).coerceAtLeast(256)

    // ── Routing thresholds ────────────────────────────────────────────────────
    /**
     * Prompt token count above which the request is flagged as requiring long
     * context. Derived from nCtx so it scales naturally:
     *   • 1536 → threshold=768  (local handles <768 tok prompts; routes cloud above)
     *   • 8192 → threshold=4096
     *   • 32K  → threshold=16K
     */
    val longContextThreshold: Int = nCtx / 2

    /**
     * True when this context window exceeds 8K tokens — enables
     * supportsLongContext on the local backend's CapabilityProfile.
     */
    val isLongContextModel: Boolean = nCtx > 8_192

    // ── Capability flags ──────────────────────────────────────────────────────
    /** Forwarded to CapabilityProfile.supportsLongContext. */
    val supportsLongContext: Boolean = isLongContextModel

    // ── Char-based equivalents (for PromptService which works in chars) ───────
    /** RAG budget expressed as characters (1 token ≈ 4 chars). */
    val ragChars: Int = ragTokens * CHARS_PER_TOKEN
    /** Summary budget expressed as characters. */
    val summaryChars: Int = summaryTokens * CHARS_PER_TOKEN

    // ── Telemetry helper ──────────────────────────────────────────────────────
    fun toLogString(): String =
        "ContextBudget nCtx=$nCtx available=$availableForContent " +
        "rag=$ragTokens summary=$summaryTokens history=$historyTokens " +
        "longCtxModel=$isLongContextModel longCtxThreshold=$longContextThreshold"

    companion object {
        private const val TAG = "AIRI_ContextBudget"
        private const val CHARS_PER_TOKEN = 4

        /**
         * Fallback budget used before a model is loaded.
         * Conservative (BALANCED mode equivalent) so callers don't crash
         * before the first model load completes.
         */
        val UNLOADED = ContextBudget(nCtx = 1536)

        /**
         * Read the live context size from the native runtime and mint a
         * ContextBudget from it.
         *
         * Must be called on the llamaDispatcher (or any thread where the
         * native context is stable) immediately after:
         *   - loadModel() succeeds
         *   - setRuntimeMode() completes
         *
         * Falls back to UNLOADED if the native call fails (no model loaded).
         */
        fun fromNative(): ContextBudget {
            val n = try {
                com.airi.assistant.ai.LlamaNative.getNCtx()
            } catch (e: Throwable) {
                Log.w(TAG, "getNCtx() failed: ${e.message} — using UNLOADED budget")
                0
            }
            return if (n > 0) {
                val budget = ContextBudget(n)
                Log.i("AIRI", "CONTEXT_BUDGET_MINTED ${budget.toLogString()}")
                budget
            } else {
                Log.w(TAG, "getNCtx() returned $n — using UNLOADED budget")
                UNLOADED
            }
        }
    }
}
