package com.airi.assistant.tools

import android.util.Log

/**
 * الذاكرة المركزية للأدوات (Tool Registry)
 * تقوم بتخزين الأدوات المكتشفة وتوفيرها للمخطط (Planner).
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"
    private val tools = mutableListOf<ToolDefinition>()

    /**
     * تسجيل قائمة من الأدوات
     */
    fun register(list: List<ToolDefinition>) {
        tools.clear()
        tools.addAll(list)
        Log.i(TAG, "Registered ${list.size} tools.")
    }

    /**
     * الحصول على جميع الأدوات المسجلة
     */
    fun getAll(): List<ToolDefinition> = tools

    /**
     * البحث عن أداة بالاسم
     */
    fun findByName(name: String): ToolDefinition? {
        return tools.find { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * البحث عن أفضل أداة بناءً على الوصف (مطابقة بسيطة حالياً)
     * سيتم تطويرها لاحقاً لاستخدام الـ Embeddings.
     */
    fun findBestMatch(intent: String): ToolDefinition? {
        return tools.find { tool ->
            intent.contains(tool.name, ignoreCase = true) || 
            tool.description.contains(intent, ignoreCase = true)
        }
    }
}
