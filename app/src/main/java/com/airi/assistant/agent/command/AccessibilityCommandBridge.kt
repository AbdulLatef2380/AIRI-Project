package com.airi.assistant.agent.command

import android.accessibilityservice.AccessibilityService
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.agent.node.NodeActionExecutor
import com.airi.assistant.agent.node.NodeScanner
import com.airi.assistant.agent.node.SemanticRanker // 🔥 استيراد المحرك الدلالي الجديد

object AccessibilityCommandBridge {

    /**
     * تنفيذ العودة لشاشة الهوم (Home)
     */
    fun launchApp(): CommandResult {
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            CommandResult(success)
        } catch (e: Exception) {
            CommandResult(false, e.message)
        }
    }

    /**
     * تنفيذ حركة "الرجوع" (Back)
     */
    fun performBack(): CommandResult {
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            CommandResult(success)
        } catch (e: Exception) {
            CommandResult(false, e.message)
        }
    }

    /**
     * 🔥 المنطق المتقدم: إدخال نص ذكي مع اختيار أفضل العناصر دلالياً
     */
    fun typeText(text: String): CommandResult {

        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")

        // 1. مسح الشاشة وجمع كل العقد المتاحة
        val nodes = NodeScanner.collectAllNodes(root)

        // 2. استخدام الـ SemanticRanker لاختيار أفضل حقل إدخال (بدل أول حقل فقط)
        val editable = SemanticRanker.rankEditableNodes(nodes)
            ?: return CommandResult(false, "No suitable editable field found")

        // 3. تنفيذ كتابة النص
        val typed = NodeActionExecutor.typeText(editable, text)
        if (!typed)
            return CommandResult(false, "Failed to type")

        // 4. البحث الدلالي عن أفضل زر "إجراء" (Action Button) بناءً على ترتيب النقاط (Scores)
        val button = SemanticRanker.rankActionButton(
            nodes,
            listOf("send", "search", "ok", "submit", "إرسال", "بحث", "تم") // دعم الكلمات الأساسية
        )

        // 5. إذا وجد الرانكر زرًا ملائمًا (بدرجة ثقة مقبولة)، يتم الضغط عليه
        button?.let {
            NodeActionExecutor.click(it)
        }

        return CommandResult(true)
    }
}
