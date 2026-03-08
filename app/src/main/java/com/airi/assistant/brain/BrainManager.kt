package com.airi.assistant.brain

import android.content.Context
import android.util.Log
import com.airi.assistant.accessibility.AIRIAccessibilityService

object BrainManager {

    private const val TAG = "AIRI_BRAIN"
    
    // الإضافة الجديدة: تهيئة الـ Context لاستخدامه في كامل الكائن
    private lateinit var context: Context

    fun init(ctx: Context) {
        context = ctx
    }

    /**
     * تحليل محتوى الشاشة واتخاذ قرار بناءً على الذاكرة أو محرك النوايا
     */
    fun processScreen(context: Context, screenText: String) {
        Log.d(TAG, "Analyzing screen context...")

        val searchKeywords = listOf(
            "search",
            "Search",
            "بحث",
            "🔍"
        )

        for (keyword in searchKeywords) {
            // استخدام UIMemory للبحث عن عناصر محفوظة مسبقاً
            val rememberedNode = UIMemory.recallNode(context, keyword)

            if (rememberedNode != null) {
                Log.i(TAG, "Memory triggered for keyword: $keyword")
                
                val intent = AiriIntent(IntentType.CLICK, keyword)
                val plan = ActionPlanner.plan(intent)

                for (step in plan) {
                    IntentEngine.execute(step)
                }
                return 
            }
        }

        // إذا لم توجد ذاكرة، يتم تحليل النص لاستخراج نية جديدة
        val intent = IntentEngine.resolve(screenText)

        if (intent != null) {
            Log.d(TAG, "New intent detected via Analysis: $intent")
            
            val plan = ActionPlanner.plan(intent)

            for (step in plan) {
                IntentEngine.execute(step)
            }
        } else {
            Log.w(TAG, "No clear intent detected for this screen. Monitoring...")
        }
    }

    /**
     * معالجة سياق الشاشة القادم من خدمة الوصول (Accessibility Service)
     */
    fun processScreenContext(contextText: String, service: AIRIAccessibilityService) {
        try {
            Log.d(TAG, "Processing context: $contextText")

            if (contextText.contains("search", true) || contextText.contains("بحث", true)) {
                Log.d(TAG, "Search related screen detected")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Context processing error", e)
        }
    }
}
