package com.airi.assistant.domain.prompt

import android.content.Context
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ai.tools.ToolRegistry

class PromptService(private val context: Context) {

    private val toolRegistry  = ToolRegistry(context)
    private val skillRegistry = SkillRegistry(context)

    fun buildSystemPrompt(
        modePrompt: String,
        responseStyle: String,
        customPrompt: String
    ): String = buildString {
        append(modePrompt)
        when (responseStyle) {
            "concise"  -> append("\nKeep your responses brief and to the point. Avoid unnecessary elaboration.")
            "detailed" -> append("\nProvide detailed, comprehensive responses with examples and explanations where helpful.")
            else       -> append("\nBalance detail and brevity in your responses.")
        }
        if (customPrompt.isNotBlank()) {
            append("\n")
            append(customPrompt)
        }
        val toolBlock = toolRegistry.buildToolDescriptionBlock()
        if (toolBlock.isNotBlank()) append(toolBlock)
        val skillBlock = skillRegistry.buildSkillDescriptionBlock()
        if (skillBlock.isNotBlank()) append(skillBlock)
    }
}
