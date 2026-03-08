package com.airi.assistant.brain

import android.content.Context
import android.util.Log
import com.airi.assistant.accessibility.AIRIAccessibilityService
// استيراد الموديلات (تأكد من مطابقة المسارات لمشروعك)
// import com.airi.assistant.models.UIScreen 

object BrainManager {

    private const val TAG = "AIRI_BRAIN"
    
    private lateinit var context: Context

    fun init(ctx: Context) {
        context = ctx
    }

    /**
     * التحليل الذكي للشاشة (نسخة Suspend للتعامل مع العمليات غير المتزامنة)
     * تستخدم الـ Hash للبحث في الذاكرة، أو تعود لمحرك القواعد في حال عدم المعرفة مسبقاً.
     */
    suspend fun analyze(screen: UIScreen, hash: String): AiriIntent? {
        Log.d(TAG, "Analyzing screen with hash: $hash")

        // 1. محاولة استرجاع الأكشن من الذاكرة (Memory)
        val memory = MemoryManager.getAction(hash)

        if (memory != null) {
            Log.i(TAG, "Memory match found! Action Type: ${memory.actionType}")
            
            // إنشاء النية بناءً على البيانات المحفوظة ديناميكياً
            return AiriIntent(
                type = IntentType.valueOf(memory.actionType),
                target = memory.targetText
            )
        }

        // 2. نظام الاحتياط: إذا لم تكن الشاشة مألوفة، نستخدم محرك النوايا (Rule-based)
        Log.d(TAG, "No memory found for this hash. Invoking IntentEngine...")
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
            
        } catch (e: Exception) {
            Log.e(TAG, "Context processing error", e)
        }
    }
}
