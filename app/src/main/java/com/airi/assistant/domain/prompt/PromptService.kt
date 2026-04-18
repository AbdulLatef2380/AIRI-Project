package com.airi.assistant.domain.prompt

import android.content.Context
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ai.tools.ToolRegistry

class PromptService(private val context: Context) {

    private val toolRegistry  = ToolRegistry(context)
    private val skillRegistry = SkillRegistry(context)

    fun buildSystemPrompt(
        modePrompt: String,
        responseStyle: String,
        customPrompt: String,
        performanceMode: PerformanceMode = PerformanceMode.BALANCED
    ): String = buildString {
        append(modePrompt)

        when (responseStyle) {
            "concise"  -> append("\nKeep your responses brief and to the point. Avoid unnecessary elaboration.")
            "detailed" -> append("\nProvide detailed, comprehensive responses with examples and explanations where helpful.")
            else       -> append("\nBalance detail and brevity in your responses.")
        }

        when (performanceMode) {
            PerformanceMode.FAST    -> append("\nRespond very concisely. Maximum 2–3 sentences unless the question strictly requires more.")
            PerformanceMode.QUALITY -> append("\nProvide thorough, well-structured answers with reasoning where helpful.")
            else                    -> Unit
        }

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

    fun isSimpleQuery(input: String): Boolean {
        val trimmed    = input.trim()
        val wordCount  = trimmed.split(Regex("\\s+")).size
        val isQuestion = trimmed.endsWith("?")
        val hasComplexKeywords = complexKeywords.any { trimmed.contains(it, ignoreCase = true) }
        return wordCount <= 8 && !hasComplexKeywords && isQuestion
    }

    private val complexKeywords = listOf(
        "explain", "compare", "describe in detail", "write", "create", "analyze",
        "summarize", "translate", "code", "function", "algorithm", "list all",
        "step by step", "how do i", "what is the difference", "pros and cons"
    )
}
