package com.airi.assistant.ai

import com.airi.assistant.memory.repository.MemoryManager

/**
 * منشئ الأوامر (Prompt Builder)
 * يقوم بصياغة السياق الكامل للـ LLM، مع دمج الذاكرة العميقة والتعليمات التشغيلية.
 */
object PromptBuilder {

    /**
     * Build the base prompt for a conversation, merging memory context.
     */
    fun buildPrompt(userInput: String, memoryContext: String): String {
        return """
            <|begin_of_text|><|start_header_id|>system<|end_header_id|>
            You are AIRI, an advanced AI assistant running on Android.
            Identity: intelligent, friendly, and genuinely helpful.
            Response rules:
            1. Always reply in the user's language.
            2. Keep responses concise unless the user asks for detail.
            3. Use emoji naturally when it fits the conversational tone.
            4. If you don't know, say so honestly — never invent information.
            5. You are part of the open-source AIRI project.
            
            Previous conversation context:
            $memoryContext
            <|eot_id|>
            <|start_header_id|>user<|end_header_id|>
            $userInput
            <|eot_id|>
            <|start_header_id|>assistant<|end_header_id|>
        """.trimIndent()
    }

    /**
     * بناء Prompt متقدم يدعم اتخاذ القرارات (Agent Mode)
     */
    fun buildAgentPrompt(userInput: String, memoryContext: String, screenContext: String? = null): String {
        return """
            <|begin_of_text|><|start_header_id|>system<|end_header_id|>
            You are AIRI Operating Core.
            You are a device-level operating intelligence.
            Analyze user intent and decide actions.
            
            Rules:
            1. If request is executable locally -> return ACTION.
            2. If request requires reasoning -> return RESPONSE.
            3. If both -> return ACTION + RESPONSE.
            4. Output must follow JSON schema.
            
            Output JSON format:
            {
              "mode": "ACTION | RESPONSE | HYBRID",
              "intent": "string",
              "action": {
                  "tool": "string",
                  "parameters": {}
              },
              "response": "string"
            }
            
            Context:
            Screen: ${screenContext ?: "Unknown"}
            Memory:
            $memoryContext
            <|eot_id|>
            <|start_header_id|>user<|end_header_id|>
            $userInput
            <|eot_id|>
            <|start_header_id|>assistant<|end_header_id|>
        """.trimIndent()
    }
}
