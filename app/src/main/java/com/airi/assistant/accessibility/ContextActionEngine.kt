package com.airi.assistant.accessibility

object ContextActionEngine {

    private const val SYSTEM_ROLE = "[System Role: You are AIRI, a contextual Android AI agent. Use the memory and screen data provided to give precise, human-like assistance.]\n"

    fun resolveActionPrompt(context: String, userQuery: String): String {
        // 1. استخراج اسم الحزمة من السياق للتحديث الذكي للذاكرة
        val currentPackage = Regex("\\[App Package: (.*?)\\]").find(context)?.groupValues?.get(1) ?: "Unknown"

        // 2. تحديد النية باستخدام الـ Scoring Engine
        val intent = IntentDetector.detectIntent(userQuery, context)

        // 3. تحديث الذاكرة قبل توليد البرومبت
        SessionMemory.update(intent, context, userQuery, currentPackage)

        // 4. اختيار الاستراتيجية
        val dynamicPrompt = when (intent) {
            IntentType.SUMMARIZE -> generateSummarizePrompt(context)
            IntentType.CODE_ANALYSIS -> generateCodeAnalysisPrompt(context, userQuery)
            IntentType.DEBUG_ERROR -> generateDebugPrompt(context, userQuery)
            IntentType.BATTERY_DIAGNOSIS -> generateBatteryPrompt(context)
            IntentType.GENERAL -> generateGeneralPromptWithMemory(context, userQuery)
            else -> generateGeneralPromptWithMemory(context, userQuery)
        }

        return SYSTEM_ROLE + dynamicPrompt
    }

    private fun generateGeneralPromptWithMemory(context: String, query: String): String {
        // إذا كان السؤال قصيراً جداً، نفترض أنه متابعة (Follow-up)
        val isFollowUp = query.length < 20 && SessionMemory.lastIntent != IntentType.GENERAL

        return if (isFollowUp) {
            """
            [FOLLOW-UP MODE]
            The user is continuing a previous task.
            Last Intent: ${SessionMemory.lastIntent}
            Previous Context: ${SessionMemory.lastContextSnapshot.take(500)}...
            
            Follow-up Question: $query
            
            Instruction: Provide a response that connects with the previous analysis.
            """.trimIndent()
        } else {
            """
            [CONTEXT AWARE MODE]
            $context
            User Query: $query
            """.trimIndent()
        }
    }

    // (بقيت الدوال generateSummarizePrompt, etc. كما هي مع إضافة $context)
    private fun generateSummarizePrompt(context: String) = "[TASK: Summarize this content]\n$context"
    private fun generateDebugPrompt(context: String, query: String) = "[TASK: Debug/Fix Error]\n$context\nUser Query: $query"
    private fun generateBatteryPrompt(context: String) = "[TASK: Analyze Battery usage]\n$context"
    private fun generateCodeAnalysisPrompt(context: String, query: String) = "[TASK: Analyze Code]\n$context\nUser Query: $query"
}
