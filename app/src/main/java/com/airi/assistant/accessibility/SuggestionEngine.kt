package com.airi.assistant.accessibility

object SuggestionEngine {

    fun generateSuggestions(context: String): List<String> {

        val suggestions = mutableListOf<String>()

        if (context.contains("متصفح ويب"))
            suggestions.add("📄 تلخيص الصفحة الحالية")

        if (context.contains("أدوات مبرمجين") &&
            context.contains("Exception"))
            suggestions.add("🐞 تحليل الخطأ البرمجي")

        if (context.contains("تطبيق محادثة"))
            suggestions.add("✍️ اقتراح رد ذكي")

        if (context.contains("إعدادات النظام"))
            suggestions.add("🔋 تحليل حالة النظام")

        return BehaviorEngine.adjustSuggestionPriority(suggestions)
    }
}
