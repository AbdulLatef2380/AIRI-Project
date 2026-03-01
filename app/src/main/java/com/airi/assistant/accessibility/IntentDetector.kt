package com.airi.assistant.accessibility

object IntentDetector {

    fun detectIntent(userQuery: String, context: String): IntentType {

        val scores = mutableMapOf<IntentType, Int>()
        IntentType.values().forEach { scores[it] = 0 }

        val q = userQuery.lowercase()
        val ctx = context.lowercase()

        // ---- Keyword Weights ----

        if (q.contains("لخص") || q.contains("summarize"))
            scores[IntentType.SUMMARIZE] = scores[IntentType.SUMMARIZE]!! + 5

        if (q.contains("خطأ") || q.contains("error"))
            scores[IntentType.DEBUG_ERROR] = scores[IntentType.DEBUG_ERROR]!! + 5

        if (q.contains("بطارية") || q.contains("battery"))
            scores[IntentType.BATTERY_DIAGNOSIS] = scores[IntentType.BATTERY_DIAGNOSIS]!! + 5

        if (ctx.contains("أدوات مبرمجين"))
            scores[IntentType.CODE_ANALYSIS] = scores[IntentType.CODE_ANALYSIS]!! + 3

        if (ctx.contains("متصفح ويب"))
            scores[IntentType.SUMMARIZE] = scores[IntentType.SUMMARIZE]!! + 2

        // ---- Pick Highest Score ----
        return scores.maxByOrNull { it.value }?.key ?: IntentType.GENERAL
    }
}
