package com.airi.assistant.domain.prompt

import android.content.Context
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.ai.QueryType
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

    fun buildSystemPrompt(
        modePrompt: String,
        responseStyle: String,
        customPrompt: String,
        performanceMode: PerformanceMode = PerformanceMode.BALANCED,
        queryType: QueryType = QueryType.UNKNOWN
    ): String = buildString {
        append(modePrompt)

        // Response-style hint
        when (responseStyle) {
            "concise"  -> append("\nKeep responses brief and to the point. Avoid unnecessary elaboration.")
            "detailed" -> append("\nProvide detailed, comprehensive responses with examples and explanations where helpful.")
            else       -> append("\nBalance detail and brevity.")
        }

        // Performance-mode hint
        when (performanceMode) {
            PerformanceMode.FAST    -> append("\nRespond very concisely. 2–3 sentences max unless strictly required.")
            PerformanceMode.QUALITY -> append("\nProvide thorough, well-structured answers with reasoning.")
            else                    -> Unit
        }

        // Query-type specific guidance
        when (queryType) {
            QueryType.SIMPLE     -> append("\nSIMPLE query: answer in 1-2 sentences ONLY. Do not expand.")
            QueryType.ANALYTICAL -> append("\nANALYTICAL query: structure your answer with clear reasoning. Stop when the point is made.")
            QueryType.ACTION     -> append("\nACTION query: deliver exactly what was requested. No commentary before or after.")
            QueryType.CREATIVE   -> append("\nCREATIVE query: be imaginative. Dive straight into the creative content.")
            QueryType.UNKNOWN    -> Unit
        }

        // Inject quality rules into every prompt
        append(QUALITY_RULES)

        if (customPrompt.isNotBlank()) {
            append("\n")
            append(customPrompt)
        }

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
}
