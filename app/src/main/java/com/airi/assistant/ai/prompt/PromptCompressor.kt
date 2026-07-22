package com.airi.assistant.ai.prompt

import android.content.Context
import android.util.Log
import com.airi.assistant.accessibility.security.AccessibilityPolicyGuard
import com.airi.assistant.memory.entity.ChatMessage

/**
 * Structured prompt compression.
 *
 * Replaces naive truncation with a 5-section envelope:
 *
 *      <System>        existing system prompt — never trimmed
 *      <Memory>        key user facts (from MemoryStore)
 *      <Summary>       compact natural-language summary of older turns
 *      <Recent Turns>  sliding window of the last RECENT_TURN_PAIRS user/assistant pairs
 *      <User Input>    the in-flight user message (added at the call site)
 *
 * Hard token-budget enforcement: the final composed prompt is capped at
 * HARD_CAP_PCT of the live n_ctx. If we exceed, we shrink the summary, then
 * drop oldest recent turns. The system prompt and memory section are never
 * dropped — they're considered load-bearing.
 *
 * NOTE: This module ONLY reshapes the prompt envelope. It does NOT change
 *       sampler params, decode logic, or KV management.
 */

object PromptCompressor {

    /** Recent conversation kept verbatim. Spec: last 4 turns. */
    const val RECENT_TURN_PAIRS = 4
    const val SOFT_TRIGGER_PCT  = 70   // when to summarize older messages
    const val HARD_CAP_PCT      = 90   // never let the composed prompt exceed this

    /**
     * Char-per-token estimate. Mixed-script CJK/Arabic ≈ 2 chars/token; ASCII
     * ≈ 4 chars/token. We pick a conservative 3.0 so we OVER-estimate token
     * counts (preferring to compress slightly too aggressively over blowing
     * past n_ctx).
     */
    private const val CHARS_PER_TOKEN = 3.0

    fun estimateTokens(s: String): Int =
        if (s.isEmpty()) 0 else (s.length / CHARS_PER_TOKEN).toInt() + 1

    data class CompressedPrompt(
        /** What to pass to LlamaManager.generateStream(systemPrompt = …). */
        val augmentedSystemPrompt: String,
        /** What to pass to LlamaManager.setHistory(…) — already trimmed. */
        val recentMessages: List<ChatMessage>,
        /** True if we should kick off a background re-summarization pass. */
        val shouldResummarize: Boolean,
        val stats: CompressionStats
    )

    data class CompressionStats(
        val totalMessagesIn:   Int,
        val totalMessagesOut:  Int,
        val tokensIn:          Int,
        val tokensOut:         Int,
        val pctReduction:      Int,
        val nCtx:              Int,
        val budgetTokens:      Int,
        val summaryTokens:     Int,
        val memoryTokens:      Int,
        val systemTokens:      Int,
        val recentTokens:      Int,
        val truncatedSummary:  Boolean,
        val droppedRecent:     Int
    )

    /**
     * @param baseSystemPrompt the plain system prompt the caller would have used
     * @param history          the full conversation history (oldest → newest), NOT
     *                         including the in-flight user message
     * @param userInput        the in-flight user message (used only for budgeting)
     * @param nCtx             live n_ctx of the current performance mode
     * @param sessionId        used to fetch memory + summary from MemoryStore
     */
    fun compose(
        ctx: Context,
        baseSystemPrompt: String,
        history: List<ChatMessage>,
        userInput: String,
        nCtx: Int,
        sessionId: String
    ): CompressedPrompt {
        val safeNCtx     = nCtx.coerceAtLeast(512)
        val budgetTokens = (safeNCtx * HARD_CAP_PCT) / 100

        // ── 1) Slice history into "older" (to summarize) vs "recent" (verbatim).
        val recentCount = (RECENT_TURN_PAIRS * 2).coerceAtMost(history.size)
        val recent      = history.takeLast(recentCount)
        val older       = history.dropLast(recentCount)

        // ── 2) Pull stored summary + facts.
        val storedSummary = MemoryStore.getSummary(ctx, sessionId)
        val coveredThrough = MemoryStore.getSummaryCoverage(ctx, sessionId)
        val facts          = MemoryStore.getFacts(ctx, sessionId)

        // Decide whether the summary is stale and a re-summarization should
        // run after this turn (caller fires-and-forgets).
        val needsResummarize = older.isNotEmpty() && coveredThrough < older.size

        // ── 3) Build the 5-section system prompt.
        var summaryToInject = storedSummary
        var truncatedSummary = false
        var droppedRecent = 0

        // Token estimates BEFORE compression: full system + full history + user.
        val tokensIn = estimateTokens(baseSystemPrompt) +
            history.sumOf { estimateTokens(it.content) } +
            estimateTokens(userInput)

        var composedSystem = buildSystemEnvelope(baseSystemPrompt, facts, summaryToInject)
        var workingRecent  = recent.toMutableList()

        fun composedTokens(): Int =
            estimateTokens(composedSystem) +
            workingRecent.sumOf { estimateTokens(it.content) } +
            estimateTokens(userInput)

        // ── 4) Enforce HARD_CAP_PCT — shrink summary, then drop oldest recent.
        var safety = 0
        while (composedTokens() > budgetTokens && safety < 32) {
            safety++
            if (summaryToInject.isNotBlank()) {
                // Drop the last 25% of the summary (it's least informative).
                val keep = (summaryToInject.length * 3 / 4).coerceAtLeast(120)
                if (summaryToInject.length > keep) {
                    summaryToInject = summaryToInject.substring(0, keep) + "…"
                    composedSystem = buildSystemEnvelope(baseSystemPrompt, facts, summaryToInject)
                    truncatedSummary = true
                    continue
                } else {
                    summaryToInject = ""
                    composedSystem = buildSystemEnvelope(baseSystemPrompt, facts, "")
                    truncatedSummary = true
                    continue
                }
            }
            if (workingRecent.isNotEmpty()) {
                workingRecent.removeAt(0)
                droppedRecent++
                continue
            }
            // Nothing left to shed — out of headroom; bail.
            Log.w("AIRI_PROMPT_COMPRESS",
                "OUT_OF_BUDGET cannot compress further; tokens=${composedTokens()} budget=$budgetTokens")
            break
        }

        val tokensOut = composedTokens()
        val pct = if (tokensIn > 0) ((tokensIn - tokensOut) * 100L / tokensIn).toInt() else 0

        val stats = CompressionStats(
            totalMessagesIn  = history.size,
            totalMessagesOut = workingRecent.size,
            tokensIn         = tokensIn,
            tokensOut        = tokensOut,
            pctReduction     = pct,
            nCtx             = safeNCtx,
            budgetTokens     = budgetTokens,
            summaryTokens    = estimateTokens(summaryToInject),
            memoryTokens     = estimateTokens(facts.joinToString("\n")),
            systemTokens     = estimateTokens(baseSystemPrompt),
            recentTokens     = workingRecent.sumOf { estimateTokens(it.content) },
            truncatedSummary = truncatedSummary,
            droppedRecent    = droppedRecent
        )

        Log.i("AIRI_PROMPT_COMPRESS",
            "msgIn=${stats.totalMessagesIn} msgOut=${stats.totalMessagesOut} " +
            "tokIn=${stats.tokensIn} tokOut=${stats.tokensOut} " +
            "reduce=${stats.pctReduction}% budget=${stats.budgetTokens}/${stats.nCtx} " +
            "sum=${stats.summaryTokens} mem=${stats.memoryTokens} " +
            "trunc=${stats.truncatedSummary} dropRecent=${stats.droppedRecent} " +
            "resummarize=$needsResummarize")

        return CompressedPrompt(
            augmentedSystemPrompt = composedSystem,
            recentMessages        = workingRecent.toList(),
            shouldResummarize     = needsResummarize &&
                (tokensIn > (safeNCtx * SOFT_TRIGGER_PCT) / 100),
            stats = stats
        )
    }

    /**
     * Glue the 5 sections into the systemPrompt string consumed by LlamaManager.
     * Sections are emitted with simple plain-text headers — they're inside the
     * model's "system" turn, so heavy XML markup would just waste tokens.
     */
    private fun buildSystemEnvelope(
        baseSystemPrompt: String,
        facts: List<String>,
        summary: String
    ): String {
        val sb = StringBuilder()
        if (baseSystemPrompt.isNotBlank()) {
            sb.append(baseSystemPrompt.trim()).append('\n')
        }
        if (facts.isNotEmpty()) {
            
            val rawFacts = facts.joinToString("\n") { "- $it" }
            val isolated = AccessibilityPolicyGuard.wrapRetrievedContent(rawFacts)
            sb.append("\n[Memory — known user facts]\n")
            sb.append(isolated).append('\n')
        }
        if (summary.isNotBlank()) {
            
            val isolated = AccessibilityPolicyGuard.wrapRetrievedContent(summary.trim())
            sb.append("\n[Summary of earlier conversation]\n")
            sb.append(isolated).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
