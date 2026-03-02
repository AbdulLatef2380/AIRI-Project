package com.airi.assistant.agent.command

import android.accessibilityservice.AccessibilityService
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.agent.node.NodeActionExecutor
import com.airi.assistant.agent.node.NodeMatcher
import com.airi.assistant.agent.node.NodeScanner

object AccessibilityCommandBridge {

    /**
     * تنفيذ العودة لشاشة الهوم
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
     * تنفيذ زر الرجوع للنظام
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
     * 🔥 المنطق الذكي لإدخال النصوص والتفاعل التلقائي
     */
    fun typeText(text: String): CommandResult {

        // 1. التحقق من اتصال الخدمة
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        // 2. الحصول على شجرة الواجهة الحالية
        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")

        // 3. مسح الشاشة بالكامل وجمع العقد (Nodes)
        val nodes = NodeScanner.collectAllNodes(root)

        // 4. البحث عن أول حقل قابل للكتابة (EditText)
        val editable = NodeMatcher.findEditableNode(nodes)
            ?: return CommandResult(false, "No editable field found")

        // 5. محاولة إدخال النص داخل الحقل المكتشف
        val typed = NodeActionExecutor.typeText(editable, text)

        if (!typed)
            return CommandResult(false, "Failed to type text")

        // 6. ذكاء إضافي: البحث التلقائي عن زر إرسال أو بحث بعد الكتابة
        val sendButton = NodeMatcher.findButtonByText(nodes, "send")
            ?: NodeMatcher.findButtonByText(nodes, "search")
            ?: NodeMatcher.findButtonByText(nodes, "إرسال") // دعم العربية

        // 7. إذا وجدنا زر مناسب، نقوم بالضغط عليه تلقائياً لإتمام المهمة
        sendButton?.let {
            NodeActionExecutor.click(it)
        }

        return CommandResult(true)
    }
}
