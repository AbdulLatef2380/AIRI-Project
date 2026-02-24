package com.airi.assistant.core

import com.airi.assistant.MemoryManager
import com.airi.assistant.planner.ExperienceStore
import kotlinx.coroutines.runBlocking

/**
 * منشئ الأوامر (Prompt Builder)
 * يقوم بصياغة السياق الكامل للـ LLM، مع دمج الخبرات السابقة (Self-Improving).
 * تم تحديثه ليدعم إدارة ميزانية السياق (Context Budget) لمنع الانفجار المعرفي.
 */
class PromptBuilder(
    private val memoryManager: MemoryManager
) {

    companion object {
        private const val MAX_CONTEXT_CHARS = 4000 // ميزانية تقريبية للسياق (Characters)
        private const val MAX_MEMORY_MESSAGES = 10
    }

    fun build(userInput: String, screenContext: String? = null): String {
        val systemIdentity = buildSystemIdentity()
        val rules = buildOperatingRules()
        val schema = buildActionSchema()
        
        // إدارة ميزانية السياق للذاكرة والخبرات
        val context = buildContext(screenContext)
        val experiences = buildExperienceContext(userInput)
        val user = "User Input: $userInput"

        val fullPrompt = listOf(
            systemIdentity,
            rules,
            schema,
            context,
            experiences,
            user
        ).joinToString("\n\n")

        return trimToBudget(fullPrompt)
    }

    private fun buildSystemIdentity(): String {
        return """
            You are AIRI Operating Core.
            You are not a chatbot.
            You are a device-level operating intelligence.
            You analyze user intent and decide actions.
            You never simulate actions.
            You generate structured decisions.
        """.trimIndent()
    }

    private fun buildOperatingRules(): String {
        return """
            Rules:
            1. If request is executable locally -> return ACTION.
            2. If request requires reasoning -> return RESPONSE.
            3. If both -> return ACTION + RESPONSE.
            4. Never describe yourself.
            5. Never explain system logic.
            6. Never output free text outside schema.
            7. Output must follow JSON schema.
            8. Think step-by-step internally but DO NOT expose reasoning. Return JSON only.
        """.trimIndent()
    }

    private fun buildActionSchema(): String {
        return """
            Output JSON format:
            {
              "mode": "ACTION | RESPONSE | HYBRID",
              "intent": "string",
              "confidence": float,
              "action": {
                  "tool": "string (name of tool to use)",
                  "type": "OPEN_APP | CLICK | READ_SCREEN | SYSTEM_CMD | NONE",
                  "parameters": {}
              },
              "response": "string"
            }
        """.trimIndent()
    }

    private fun buildContext(screenContext: String?): String {
        val memoryContext = runBlocking { 
            memoryManager.getRecentMessages(MAX_MEMORY_MESSAGES)
                .joinToString("\n") { "${it.sender}: ${it.content}" }
        }
        
        return """
            Context:
            Screen: ${screenContext?.take(500) ?: "Unknown"}
            
            Memory:
            ${truncateMemory(memoryContext, 1500)}
        """.trimIndent()
    }

    private fun buildExperienceContext(userInput: String): String {
        val experiences = runBlocking { ExperienceStore.getBestExperiences(userInput, 2) }
        if (experiences.isEmpty()) return ""

        val expText = experiences.joinToString("\n---\n") { exp ->
            "Goal: ${exp.goal}\nPlan: ${exp.plan}\nResult: ${exp.result}\nScore: ${exp.score}"
        }

        return """
            Past Experiences (Learn from these):
            ${expText.take(1000)}
        """.trimIndent()
    }

    /**
     * تقليم السياق لضمان عدم تجاوز حدود النموذج (Context Budget)
     */
    private fun trimToBudget(prompt: String): String {
        if (prompt.length <= MAX_CONTEXT_CHARS) return prompt
        
        // إذا تجاوز الطول، نقوم بتقليم الأجزاء الأقل أهمية (مثل وسط الذاكرة)
        // هنا نكتفي بالتقليم البسيط حالياً لضمان الاستقرار
        return prompt.take(MAX_CONTEXT_CHARS)
    }

    private fun truncateMemory(memory: String, maxChars: Int): String {
        if (memory.length <= maxChars) return memory
        return "...[Older messages truncated]...\n" + memory.takeLast(maxChars)
    }
}
