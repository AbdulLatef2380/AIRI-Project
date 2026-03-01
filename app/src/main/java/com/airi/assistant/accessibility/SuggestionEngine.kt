package com.airi.assistant.accessibility

object SuggestionEngine {

    fun generateSuggestion(context: String): String? {
        val ctx = context.lowercase()
        val category = Regex("\\[App Category: (.*?)\\]").find(context)?.groupValues?.get(1) ?: ""

        return when {
            // حالة المتصفح
            category.contains("متصفح ويب") -> "📄 هل تريد تلخيص هذه الصفحة؟"

            // حالة البرمجة والخطأ
            category.contains("أدوات مبرمجين") && (ctx.contains("exception") || ctx.contains("error")) -> 
                "🐞 هل أساعدك في حل هذا الخطأ البرمجي؟"

            // حالة المحادثات
            category.contains("تطبيق محادثة") -> "✍️ هل أقترح عليك رداً ذكياً؟"

            // حالة الإعدادات والبطارية
            category.contains("إعدادات") && (ctx.contains("battery") || ctx.contains("بطارية")) -> 
                "🔋 هل تريد تحليل استهلاك البطارية؟"

            else -> null
        }
    }
}
