package com.airi.assistant.accessibility

object IntentDetector {
    fun detectIntent(userQuery: String, context: String): IntentType {
        val q = userQuery.lowercase()

        return when {
            // 1. التعرف على نية التلخيص
            q.contains("لخص") || q.contains("summarize") || q.contains("ملخص") -> 
                IntentType.SUMMARIZE

            // 2. التعرف على نية حل المشاكل/الأخطاء
            q.contains("خطأ") || q.contains("error") || q.contains("crash") || q.contains("مشكلة") -> 
                IntentType.DEBUG_ERROR

            // 3. التعرف على نية فحص البطارية (فقط إذا كان في الإعدادات)
            (q.contains("بطارية") || q.contains("battery")) && context.contains("إعدادات") -> 
                IntentType.BATTERY_DIAGNOSIS

            // 4. التعرف على سياق البرمجة تلقائياً
            context.contains("أدوات مبرمجين") -> 
                IntentType.CODE_ANALYSIS

            // 5. الحالة الافتراضية
            else -> IntentType.GENERAL
        }
    }
}
