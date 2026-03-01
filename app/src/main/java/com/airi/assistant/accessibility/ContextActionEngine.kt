package com.airi.assistant.accessibility

object ContextActionEngine {

    // الـ Role الثابت لضمان شخصية AIRI
    private const val SYSTEM_ROLE = "[System Role: You are AIRI, a highly capable contextual Android AI assistant. Always be concise, helpful, and aware of the screen data provided.]\n"

    fun resolveActionPrompt(context: String, userQuery: String): String {
        // 1. تحديد النية (Intent Detection)
        val intent = IntentDetector.detectIntent(userQuery, context)

        // 2. اختيار البرومبت المناسب بناءً على النية
        val dynamicPrompt = when (intent) {
            IntentType.SUMMARIZE -> generateSummarizePrompt(context)
            IntentType.CODE_ANALYSIS -> generateCodeAnalysisPrompt(context, userQuery)
            IntentType.DEBUG_ERROR -> generateDebugPrompt(context, userQuery)
            IntentType.BATTERY_DIAGNOSIS -> generateBatteryPrompt(context)
            else -> generateGeneralPrompt(context, userQuery)
        }

        // 3. دمج الـ Role مع البرومبت المختار
        return SYSTEM_ROLE + dynamicPrompt
    }

    private fun generateSummarizePrompt(context: String) = """
        $context
        Task: قم بتلخيص المحتوى الظاهر على الشاشة. ركز على النقاط الجوهرية والنتائج النهائية.
    """.trimIndent()

    private fun generateCodeAnalysisPrompt(context: String, query: String) = """
        $context
        Context: User is a developer looking at code.
        Query: $query
        Task: حلل الكود برمجياً، اشرح المنطق، واقترح تحسينات إذا لزم الأمر.
    """.trimIndent()

    private fun generateDebugPrompt(context: String, query: String) = """
        $context
        Task: المستخدم يواجه خطأ تقنياً. ابحث في محتوى الشاشة عن رسائل الخطأ (Error Messages) أو الـ StackTrace وقدم حلاً فورياً.
    """.trimIndent()

    private fun generateBatteryPrompt(context: String) = """
        $context
        Task: تحليل حالة البطارية. استخرج النسبة المئوية والتطبيقات المستهلكة من الشاشة وقدم نصيحة لتحسين عمر البطارية.
    """.trimIndent()

    private fun generateGeneralPrompt(context: String, query: String) = """
        $context
        User Question: $query
        Task: أجب على سؤال المستخدم بناءً على السياق الموفر أعلاه.
    """.trimIndent()
}
