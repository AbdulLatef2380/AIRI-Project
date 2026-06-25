package com.airi.assistant.domain.prompt

import android.content.Context
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.context.ContextBudget
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ai.tools.ToolRegistry

class PromptService(private val context: Context) {

    private val toolRegistry  = ToolRegistry(context)
    private val skillRegistry = SkillRegistry(context)

    // ── Quality rules injected into every system prompt ───────────────────────
    private val QUALITY_RULES = """

STRICT RESPONSE RULES — follow every rule exactly:
1. Lead with the answer immediately. No preamble, no warm-up.
2. Be concise. One sentence for greetings, 2-3 sentences for simple facts.
3. Never use filler: no "Certainly!", "Of course!", "Sure!", "Great question!".
4. Stop writing as soon as the answer is complete. Do not pad or summarize.
5. If unsure, say so briefly. Never fabricate facts.
6. Do not repeat or rephrase the user's question in your reply.
7. Use bullet points only when listing 3 or more distinct items.
8. Match the exact language the user writes in (Arabic → Arabic, English → English).
9. Never apologize for being concise.
10. If asked for a single thing, give exactly one thing."""

    /**
     * Build the system prompt WITHOUT RAG context (legacy path, no memory injection).
     * Use [buildSystemPromptWithContext] for full Phase 2 dynamic injection.
     */
    fun buildSystemPrompt(
        modePrompt: String,
        responseStyle: String,
        customPrompt: String,
        performanceMode: PerformanceMode = PerformanceMode.BALANCED,
        queryType: QueryType = QueryType.UNKNOWN
    ): String = buildSystemPromptWithContext(
        modePrompt      = modePrompt,
        responseStyle   = responseStyle,
        customPrompt    = customPrompt,
        performanceMode = performanceMode,
        queryType       = queryType,
        ragContextBlock = "",
        memorySummary   = ""
    )

    /**
     * Build the fully-dynamic system prompt with RAG memory injection.
     *
     * Phase 2 upgrade over [buildSystemPrompt]: this variant accepts the
     * pre-retrieved semantic memory block from [RagRetriever.buildContextBlock]
     * and the compressed conversation summary from [ConversationSummarizer].
     *
     * SPRINT 1 upgrade: accepts an optional [contextBudget] derived from
     * LlamaNative.getNCtx() via LlamaManager.contextBudget. When provided,
     * RAG and summary char caps scale with nCtx instead of using the former
     * hardcoded constants (MAX_RAG_CHARS=2400, MAX_SUMMARY_CHARS=1600):
     *   nCtx=1536  → ragChars=368,  summaryChars=184
     *   nCtx=4096  → ragChars=1080, summaryChars=540
     *   nCtx=32768 → ragChars=4096, summaryChars=1600  (clamped)
     *
     * ── INJECTION ORDER ──────────────────────────────────────────────────────
     *   1. Agent mode persona ([modePrompt])
     *   2. Conversation summary (older turns, if provided)
     *   3. RAG context block (semantic memory hits, if provided)
     *   4. Response-style / performance hints
     *   5. Quality rules (always)
     *   6. User custom prompt override
     *   7. Tool descriptions (omitted in FAST mode)
     *   8. Skill descriptions (omitted in FAST mode)
     *
     * @param ragContextBlock   Formatted RAG block from [RagRetriever.buildContextBlock].
     *                          Empty string → slot is skipped. Trimmed to
     *                          [contextBudget.ragChars] (or [MAX_RAG_CHARS] if no budget).
     * @param memorySummary     Compressed summary of older turns from
     *                          [ConversationSummarizer]. Empty → omitted.
     * @param contextBudget     Live budget from LlamaManager.contextBudget.
     *                          Defaults to [ContextBudget.UNLOADED] for backward compat.
     */
    fun buildSystemPromptWithContext(
        modePrompt:      String,
        responseStyle:   String,
        customPrompt:    String,
        performanceMode: PerformanceMode = PerformanceMode.BALANCED,
        queryType:       QueryType       = QueryType.UNKNOWN,
        ragContextBlock: String          = "",
        memorySummary:   String          = "",
        contextBudget:   ContextBudget   = ContextBudget.UNLOADED
    ): String = buildString {
        // ── 1. Agent persona ───────────────────────────────────────────────────
        append(modePrompt)

        // ── 2. Conversation summary (compressed older turns) ────────────────────
        // SPRINT 1: char cap derived from live ContextBudget when available,
        // falls back to static MAX_SUMMARY_CHARS for backward compatibility.
        val summaryCharCap = if (contextBudget.nCtx > ContextBudget.UNLOADED.nCtx)
            contextBudget.summaryChars else MAX_SUMMARY_CHARS
        if (memorySummary.isNotBlank()) {
            append("\n\n--- Conversation summary (prior context) ---\n")
            append(memorySummary.trim().take(summaryCharCap))
            append("\n--- End of summary ---")
        }

        // ── 3. RAG semantic memory block ────────────────────────────────────────
        // SPRINT 1: char cap derived from live ContextBudget when available.
        val ragCharCap = if (contextBudget.nCtx > ContextBudget.UNLOADED.nCtx)
            contextBudget.ragChars else MAX_RAG_CHARS
        val trimmedRag = ragContextBlock.trim().take(ragCharCap)
        if (trimmedRag.isNotBlank()) {
            append("\n\n")
            append(trimmedRag)
        }

        // ── 4. Response-style hint ──────────────────────────────────────────────
        when (responseStyle) {
            "concise"  -> append("\nKeep responses brief and to the point. Avoid unnecessary elaboration.")
            "detailed" -> append("\nProvide detailed, comprehensive responses with examples and explanations where helpful.")
            else       -> append("\nBalance detail and brevity.")
        }

        // ── 5. Performance-mode hint ────────────────────────────────────────────
        when (performanceMode) {
            PerformanceMode.FAST    -> append("\nRespond very concisely. 2–3 sentences max unless strictly required.")
            PerformanceMode.QUALITY -> append("\nProvide thorough, well-structured answers with reasoning.")
            else                    -> Unit
        }

        // ── 6. Query-type specific guidance ─────────────────────────────────────
        when (queryType) {
            QueryType.SIMPLE     -> append("\nSIMPLE query: answer in 1-2 sentences ONLY. Do not expand.")
            QueryType.ANALYTICAL -> append("\nANALYTICAL query: structure your answer with clear reasoning. Stop when the point is made.")
            QueryType.ACTION     -> append("\nACTION query: deliver exactly what was requested. No commentary before or after.")
            QueryType.CREATIVE   -> append("\nCREATIVE query: be imaginative. Dive straight into the creative content.")
            QueryType.UNKNOWN    -> Unit
        }

        // ── 7. Quality rules (always injected) ──────────────────────────────────
        append(QUALITY_RULES)

        if (customPrompt.isNotBlank()) {
            append("\n")
            append(customPrompt)
        }

        // ── 8. Tool + Skill blocks (omitted in FAST mode for latency) ────────────
        if (performanceMode != PerformanceMode.FAST) {
            val toolBlock = toolRegistry.buildToolDescriptionBlock()
            if (toolBlock.isNotBlank()) append(toolBlock)
            val skillBlock = skillRegistry.buildSkillDescriptionBlock()
            if (skillBlock.isNotBlank()) append(skillBlock)
        }
    }

    /**
     * Build a minimal prompt for simple/fast queries — skips all registry overhead.
     */
    fun buildSimpleSystemPrompt(agentModePrompt: String, maxTokens: Int): String =
        "$agentModePrompt\n${QUALITY_RULES}\nAnswer immediately in 1-2 sentences. Hard cap: $maxTokens tokens. Stop as soon as answered."

    fun isSimpleQuery(input: String): Boolean {
        val trimmed   = input.trim()
        val wordCount = trimmed.split(Regex("\\s+")).size
        val hasComplexKeywords = complexKeywords.any { trimmed.contains(it, ignoreCase = true) }
        return wordCount <= 8 && !hasComplexKeywords
    }

    private val complexKeywords = listOf(
        "explain", "compare", "describe in detail", "write", "create", "analyze",
        "summarize", "translate", "code", "function", "algorithm", "list all",
        "step by step", "how do i", "what is the difference", "pros and cons",
        "اشرح", "قارن", "اكتب", "أنشئ", "حلل", "لخّص", "ترجم", "الفرق بين"
    )

    private companion object {
        // Static char caps — SPRINT 1 migration note:
        // These are now fallback values only, used when no live ContextBudget
        // is available (e.g. before model load). After model load, the caller
        // should pass contextBudget from LlamaManager.contextBudget so caps
        // scale automatically with nCtx (4K, 8K, 32K+ models).
        //
        // Historical values documented for the migration report:
        //   MAX_RAG_CHARS     = 2400  (~600 tok) — was hardcoded for 4K models
        //   MAX_SUMMARY_CHARS = 1600  (~400 tok) — was hardcoded for 4K models
        const val MAX_RAG_CHARS     = 2_400
        const val MAX_SUMMARY_CHARS = 1_600
    }
}
