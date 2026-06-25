package com.airi.assistant.ai.prompt.budget

/**
 * ContributorBudgetPolicy — the single source of truth for ALL context-window
 * allocation fractions, fixed overhead constants, and per-contributor caps.
 *
 * ## Design contract (Phase B)
 *   • No other file may hardcode a context percentage, overhead constant, or
 *     contributor cap.  Every magic number that governs how the context window
 *     is divided lives exclusively here.
 *   • New contributors add an entry here and claim through [PromptBudgetLedger].
 *   • Changing a fraction propagates automatically to [ContextBudget],
 *     [AgentLoop], [DynamicPromptEngine], [ConversationSummarizer], and every
 *     future contributor with zero scattered edits.
 *
 * ## Current allocation table
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  FIXED OVERHEAD — always reserved before any dynamic content             │
 * │    SYSTEM_OVERHEAD_TOKENS       = 200  persona + quality rules base       │
 * │    TEMPLATE_OVERHEAD_TOKENS     =  80  chat-template structural markers   │
 * │    USER_FRAGMENT_RESERVE_TOKENS =  80  in-flight user message fragment    │
 * │    GENERATION_RESERVE_TOKENS    = 256  output headroom, never for input   │
 * │                                                                          │
 * │  DYNAMIC CONTENT — carved from availableForContent = nCtx - overhead     │
 * │    RAG             20 % of available   clamped [64 .. 1 024] tokens       │
 * │    SUMMARY         10 % of available   clamped [64 ..   400] tokens       │
 * │    TOOLS           25 % of available   min 512 chars safety floor         │
 * │    EXTRA_CONTEXT   fixed 800 chars  (≈ 200 tokens) per call               │
 * │    SKILLS          ledger remainder after RAG + SUMMARY + TOOLS           │
 * │    HISTORY         ledger remainder after all system-level claims          │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * ## Thread-safety
 * This is a pure Kotlin `object` with only `val` and `fun` members.
 * No state — safe to read from any thread without synchronisation.
 *
 * ## Scalability
 * When a new prompt contributor is introduced (e.g. MCP context, calendar
 * feed, screen content):
 *   1. Add its fraction / cap constant here with a KDoc comment.
 *   2. Add a `Contributor` entry to [PromptBudgetLedger.Contributor].
 *   3. Call `ledger.claim(Contributor.YOUR_NEW_SLOT, estimated)` before appending.
 *   4. No other file needs editing.
 */
object ContributorBudgetPolicy {

    // ── Fixed overhead ────────────────────────────────────────────────────────
    // These are tokens that are ALWAYS consumed by the conversation infrastructure,
    // regardless of how much dynamic content is injected.  They are subtracted from
    // nCtx before any dynamic contributor may claim budget.

    /** Tokens reserved for the agent persona / base system instruction text. */
    const val SYSTEM_OVERHEAD_TOKENS: Int = 200

    /** Tokens for chat-template structural markers (im_start, role tags, eot_id, etc.). */
    const val TEMPLATE_OVERHEAD_TOKENS: Int = 80

    /**
     * Tokens reserved for the in-flight user message fragment.
     * This accounts for the current user turn being appended after the system
     * prompt and history, so the generation cannot silently truncate user input.
     */
    const val USER_FRAGMENT_RESERVE_TOKENS: Int = 80

    /**
     * Tokens reserved for model output.
     * The native context must have room for at least this many output tokens
     * before the model can generate a useful response.
     */
    const val GENERATION_RESERVE_TOKENS: Int = 256

    // ── RAG semantic memory block ─────────────────────────────────────────────

    /** RAG block fraction of [ContextBudget.availableForContent]. */
    const val RAG_FRACTION: Double = 0.20

    /** Minimum RAG tokens granted even on the tightest context window. */
    const val RAG_TOKENS_MIN: Int = 64

    /**
     * Maximum RAG tokens granted even on the largest context window.
     * Prevents RAG from monopolising a 32K context — history should dominate.
     */
    const val RAG_TOKENS_MAX: Int = 1_024

    // ── Conversation summary block ────────────────────────────────────────────

    /** Conversation summary fraction of [ContextBudget.availableForContent]. */
    const val SUMMARY_FRACTION: Double = 0.10

    /** Minimum summary tokens granted. */
    const val SUMMARY_TOKENS_MIN: Int = 64

    /** Maximum summary tokens granted. */
    const val SUMMARY_TOKENS_MAX: Int = 400

    // ── Tool schema block ─────────────────────────────────────────────────────

    /**
     * Tool schema block fraction of [ContextBudget.availableForContent].
     * Gives a practical ceiling: ≈ 384 chars on a 1 536-token context (up to
     * ~10 compact tool entries) and ≈ 7 500 chars on a 32K model (50+ tools).
     */
    const val TOOL_FRACTION: Double = 0.25

    /**
     * Minimum characters for the tool block regardless of available budget.
     * Ensures at least the first few tool definitions are visible to the LLM
     * even on severely constrained context windows.
     */
    const val TOOL_CHARS_MIN: Int = 512

    // ── Extra / injected context ──────────────────────────────────────────────

    /**
     * Maximum characters for caller-injected extra context
     * (screen content, accessibility data, calendar snippets, etc.).
     * Any single extra-context block is truncated to this limit before
     * being appended to the system prompt.
     */
    const val EXTRA_CONTEXT_MAX_CHARS: Int = 800

    // ── Shared constants ──────────────────────────────────────────────────────

    /**
     * Conservative chars-per-token ratio used consistently by every contributor.
     * Matches the ratio in [PromptBudgetLedger], [DynamicPromptEngine], and
     * [PromptCompressor] so token estimates are identical across the stack.
     */
    const val CHARS_PER_TOKEN: Int = 4

    // ── Derived totals ────────────────────────────────────────────────────────

    /**
     * Total fixed overhead tokens subtracted from nCtx before any dynamic
     * contributor may claim budget.
     */
    val TOTAL_FIXED_OVERHEAD: Int =
        SYSTEM_OVERHEAD_TOKENS + TEMPLATE_OVERHEAD_TOKENS +
        USER_FRAGMENT_RESERVE_TOKENS + GENERATION_RESERVE_TOKENS

    // ── Policy computation helpers ────────────────────────────────────────────

    /**
     * Compute the RAG token budget for a given [availableForContent].
     * Applies [RAG_FRACTION] and clamps to [[RAG_TOKENS_MIN]..[RAG_TOKENS_MAX]].
     */
    fun ragTokens(availableForContent: Int): Int =
        (availableForContent * RAG_FRACTION).toInt()
            .coerceIn(RAG_TOKENS_MIN, RAG_TOKENS_MAX)

    /**
     * Compute the summary token budget for a given [availableForContent].
     * Applies [SUMMARY_FRACTION] and clamps to [[SUMMARY_TOKENS_MIN]..[SUMMARY_TOKENS_MAX]].
     */
    fun summaryTokens(availableForContent: Int): Int =
        (availableForContent * SUMMARY_FRACTION).toInt()
            .coerceIn(SUMMARY_TOKENS_MIN, SUMMARY_TOKENS_MAX)

    /**
     * Compute the tool-block character cap for a given [availableForContent].
     * Applies [TOOL_FRACTION] (converting tokens → chars) and enforces [TOOL_CHARS_MIN].
     */
    fun toolCharsCap(availableForContent: Int): Int =
        (availableForContent * TOOL_FRACTION * CHARS_PER_TOKEN).toInt()
            .coerceAtLeast(TOOL_CHARS_MIN)
}
