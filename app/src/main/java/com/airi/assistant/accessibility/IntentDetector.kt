package com.airi.assistant.accessibility

object IntentDetector {

    fun detectIntent(userQuery: String = "", context: String): IntentType {
        val scores = mutableMapOf<IntentType, Int>()
        // تأكد أن IntentType.values() موجود في مشروعك، وإلا عرف الـ Enum
        IntentType.values().forEach { scores[it] = 0 }

        val q = userQuery.lowercase()
        val ctx = context.lowercase()

        // 🎯 1. تحليل الكلمات المفتاحية من المستخدم (وزن مرتفع: 5)
        if (q.isNotBlank()) {
            if (q.contains("لخص") || q.contains("summarize")) scores[IntentType.SUMMARIZE] = 5
            if (q.contains("خطأ") || q.contains("error")) scores[IntentType.DEBUG_ERROR] = 5
            if (q.contains("بطارية") || q.contains("battery")) scores[IntentType.BATTERY_DIAGNOSIS] = 5
        }

        // 👁️ 2. تحليل السياق التلقائي (وزن متوسط: 3)
        // هذا الجزء هو "محرك المبادرة"
        if (ctx.contains("exception") || ctx.contains("error") || ctx.contains("stacktrace")) {
            scores[IntentType.DEBUG_ERROR] = scores[IntentType.DEBUG_ERROR]!! + 4
        }
        
        if (ctx.contains("أدوات مبرمجين")) {
            scores[IntentType.CODE_ANALYSIS] = scores[IntentType.CODE_ANALYSIS]!! + 3
        }

        if (ctx.contains("متصفح ويب") || ctx.length > 3000) {
            scores[IntentType.SUMMARIZE] = scores[IntentType.SUMMARIZE]!! + 2
        }
        
        if (ctx.contains("whatsapp") || ctx.contains("telegram") || ctx.contains("chat")) {
            // سنفترض وجود نوع للمحادثات، إذا لم يوجد استخدم GENERAL
            // scores[IntentType.SMART_REPLY] = 3 
        }

        // 🏆 اختيار النية الأعلى نقاطاً
        return scores.maxByOrNull { it.value }?.let { 
            if (it.value > 0) it.key else IntentType.GENERAL 
        } ?: IntentType.GENERAL
    }
}
