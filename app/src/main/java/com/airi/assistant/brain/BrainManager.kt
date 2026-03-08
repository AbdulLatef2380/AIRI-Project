package com.airi.assistant.brain

import android.content.Context
import android.util.Log
import com.airi.assistant.accessibility.AIRIAccessibilityService
// تأكد من استيراد الكلاسات التالية من حزمها الصحيحة في مشروعك
// import com.airi.assistant.models.UIScreen 

object BrainManager {

    private const val TAG = "AIRI_BRAIN"
    
    private lateinit var context: Context

    fun init(ctx: Context) {
        context = ctx
    }

    /**
     * التحليل الذكي للشاشة بناءً على الذاكرة (Hash) أو القواعد (Rules)
     */
    fun analyze(screen: UIScreen, hash: String): AiriIntent? {
        Log.d(TAG, "Analyzing screen with hash: $hash")

        // 1. البحث في الذاكرة باستخدام الـ Hash الخاص بالشاشة
        val memory = MemoryManager.getActions(hash)

        if (memory != null) {
            Log.i(TAG, "Memory match found for this screen state.")
            return AiriIntent(
                type = IntentType.CLICK,
                target = memory.target
            )
        }

        // 2. إذا لم توجد ذاكرة، ننتقل لمحرك النوايا (التحليل المبني على القواعد)
        Log.d(TAG, "No memory found. Falling back to IntentEngine.")
        return IntentEngine.detect(screen)
    }

    /**
     * معالجة سياق الشاشة القادم من خدمة الوصول
     */
    fun processScreenContext(contextText: String, service: AIRIAccessibilityService) {
        try {
            Log.d(TAG, "Processing context: $contextText")

            if (contextText.contains("search", true) || contextText.contains("بحث", true)) {
                Log.d(TAG, "Search related screen detected")
            }
            
            // هنا يمكنك مستقبلاً استدعاء analyze بعد تحويل الشاشة لـ UIScreen
        } catch (e: Exception) {
            Log.e(TAG, "Context processing error", e)
        }
    }
}
