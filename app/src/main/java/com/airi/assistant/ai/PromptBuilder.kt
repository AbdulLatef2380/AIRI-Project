package com.airi.assistant.ai

import com.airi.assistant.memory.repository.MemoryManager

/**
 * منشئ الأوامر (Prompt Builder)
 * يقوم بصياغة السياق الكامل للـ LLM، مع دمج الذاكرة العميقة والتعليمات التشغيلية.
 */
object PromptBuilder {

    /**
     * بناء الـ Prompt الأساسي للمحادثة مع دمج السياق
     */
    fun buildPrompt(userInput: String, memoryContext: String): String {
        return """
            <|begin_of_text|><|start_header_id|>system<|end_header_id|>
            أنت AIRI، المساعد الذكي المتطور بنظام Android.
            هويتك: ذكي، مرح، ومفيد جداً.
            قواعد الرد:
            1. أجب دائماً باللغة العربية (لهجة بيضاء مفهومة أو فصحى بسيطة).
            2. اجعل ردودك قصيرة ومباشرة (إلا إذا طلب المستخدم تفاصيل).
            3. استخدم الرموز التعبيرية (Emojis) بشكل لطيف لتظهر شخصيتك الودودة.
            4. إذا لم تعرف الإجابة، قل ذلك بصدق ولا تخترع معلومات.
            5. تذكر دائماً أنك جزء من مشروع AIRI المفتوح المصدر.
            
            سياق المحادثة السابقة:
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
