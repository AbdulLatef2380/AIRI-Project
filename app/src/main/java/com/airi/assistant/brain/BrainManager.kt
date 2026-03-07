package com.airi.assistant.brain

import android.content.Context
import android.util.Log

object BrainManager {

    private const val TAG = "AIRI_BRAIN"

    /**
     * معالجة نص الشاشة واتخاذ قرار بناءً على الذاكرة التراكمية أو التحليل اللحظي
     */
    fun processScreen(context: Context, screenText: String) {

        Log.d(TAG, "Analyzing screen context...")

        // 🔹 1. البحث في الذاكرة باستخدام عدة كلمات مفتاحية (دعم لغات متعددة)
        val searchKeywords = listOf(
            "search",
            "Search",
            "بحث",
            "🔍"
        )

        for (keyword in searchKeywords) {
            // محاولة استدعاء العنصر من الذاكرة بناءً على الكلمة
            val rememberedNode = UIMemory.recallNode(context, keyword)

            if (rememberedNode != null) {
                Log.i(TAG, "Memory triggered for keyword: $keyword")
                
                // تنفيذ الضغط فوراً بناءً على ما تم تذكره
                IntentEngine.execute("click:$keyword")
                return // إنهاء المعالجة هنا لأننا وجدنا الهدف في الذاكرة
            }
        }

        // ⚡ 2. التحليل اللحظي (إذا لم تسعفنا الذاكرة)
        val command = IntentEngine.resolve(screenText)

        if (command != null) {
            Log.d(TAG, "New command detected via Analysis: $command")
            IntentEngine.execute(command)
            
            // 💡 نصيحة: يمكننا هنا إضافة كود لحفظ النجاح الجديد في الذاكرة تلقائياً
        } else {
            Log.w(TAG, "No clear intent detected for this screen. Monitoring...")
        }
    }
}
