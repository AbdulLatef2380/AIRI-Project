package com.airi.assistant.core

import com.airi.assistant.MemoryManager

class PromptBuilder(
    private val memoryManager: MemoryManager
) {

    fun build(userInput: String, screenContext: String? = null): String {
        val systemIdentity = buildSystemIdentity()
        val rules = buildOperatingRules()
        val schema = buildActionSchema()
        val context = buildContext(screenContext)
        val user = "User Input: $userInput"

        return listOf(
            systemIdentity,
            rules,
            schema,
            context,
            user
        ).joinToString("\n\n")
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
                  "type": "OPEN_APP | CLICK | READ_SCREEN | SYSTEM_CMD | NONE",
                  "parameters": {}
              },
              "response": "string"
            }
        """.trimIndent()
    }

    private fun buildContext(screenContext: String?): String {
        val memoryContext = memoryManager.getRecentMessages(5).joinToString("\n") { it.content }
        return """
            Context:
            Screen: ${screenContext ?: "Unknown"}
            
            Memory:
            $memoryContext
        """.trimIndent()
    }
}
