package com.airi.assistant.accessibility

object ContextActionEngine {

    // تحديد الاستراتيجية بناءً على السياق
    fun resolveActionPrompt(context: String, userQuery: String): String {
        return when {
            // حالة التلخيص التلقائي في المتصفح
            context.contains("متصفح ويب") && (userQuery.isEmpty() || userQuery.contains("لخص")) -> {
                generateSummarizePrompt(context)
            }
            
            // حالة تحليل الكود في تطبيقات المطورين
            context.contains("أدوات مبرمجين") -> {
                generateCodeAnalysisPrompt(context, userQuery)
            }

            // الافتراضي: دمج السياق مع سؤال المستخدم
            else -> """
                [Context Awareness Mode]
                $context
                User Instruction: $userQuery
            """.trimIndent()
        }
    }

    private fun generateSummarizePrompt(context: String) = """
        $context
        Task: قم بتلخيص هذه الصفحة بشكل مركز جداً.
        Format:
        - العنوان الرئيسي
        - ملخص في 3 نقاط
        - أهم الروابط أو الأرقام إن وجدت
    """.trimIndent()

    private fun generateCodeAnalysisPrompt(context: String, query: String) = """
        $context
        User is a Developer. Query: $query
        Task: حلل الكود الموضح في الشاشة وقدم حلاً تقنياً مباشراً.
    """.trimIndent()
}
