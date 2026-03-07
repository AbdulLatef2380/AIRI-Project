package com.airi.assistant.brain

import android.content.Context
import android.util.Log

object BrainManager {

    private const val TAG = "AIRI_BRAIN"

    /**
     * معالجة نص الشاشة واتخاذ قرار بناءً على الذكاء اللحظي أو الذاكرة السابقة
     */
    fun processScreen(context: Context, screenText: String) {

        Log.d(TAG, "Analyzing screen context...")

        // 1. 🧠 محاولة استدعاء الذاكرة (Memory Recall)
        // إذا سبق لـ AIRI التفاعل مع زر "البحث" في هذا التطبيق
        val rememberedNode = UIMemory.recallNode(context, "search")
        
        if (rememberedNode != null) {
            Log.i(TAG, "Memory triggered: Found previously used 'search' node")
            IntentEngine.execute("click:Search")
            return // التوقف هنا لأننا وجدنا الحل في الذاكرة
        }

        // 2. ⚡ إذا لم تكن هناك ذاكرة، نستخدم محرك النوايا (Intent Engine) للتحليل اللحظي
        val command = IntentEngine.resolve(screenText)

        if (command != null) {
            Log.d(TAG, "New command detected via Analysis: $command")
            
            // تنفيذ الأمر
            IntentEngine.execute(command)
            
            // 💾 اختيارياً: يمكنك حفظ هذا النجاح في الذاكرة هنا لكي يتذكره لاحقاً
            // UIMemory.storeNode(context, "search", ...)
        } else {
            Log.w(TAG, "No clear intent detected for this screen.")
        }
    }
}
