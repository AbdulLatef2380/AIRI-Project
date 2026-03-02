package com.airi.assistant.agent.command

import android.accessibilityservice.AccessibilityService
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.agent.node.NodeActionExecutor
import com.airi.assistant.agent.node.NodeScanner
import com.airi.assistant.agent.node.SemanticRanker
import com.airi.assistant.agent.reinforcement.ReinforcementMemory // 🔥 استيراد الذاكرة التعزيزية

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
     * 🔥 المنطق المتقدم: إدخال نص مع تسجيل النتائج في الذاكرة لتعلم الأنماط
     */
    fun typeText(text: String): CommandResult {

        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")

        // 1. مسح الشاشة بالكامل
        val nodes = NodeScanner.collectAllNodes(root)

        // 2. اختيار أفضل حقل إدخال (Ranking)
        val editable = SemanticRanker.rankEditableNodes(nodes)
            ?: return CommandResult(false, "No suitable editable field found")

        val editableKey = "editable_${editable.hintText ?: editable.viewIdResourceName}"

        // 3. محاولة إدخال النص
        val typed = NodeActionExecutor.typeText(editable, text)
        
        if (typed) {
            // ✅ سجل نجاح الكتابة في هذا الحقل
            ReinforcementMemory.recordSuccess(editableKey)
        } else {
            // ❌ سجل فشل الكتابة
            ReinforcementMemory.recordFailure(editableKey)
            return CommandResult(false, "Failed to type")
        }

        // 4. البحث الدلالي عن زر الإجراء (Action Button)
        val button = SemanticRanker.rankActionButton(
            nodes,
            listOf("send", "search", "ok", "submit", "إرسال", "بحث", "تم")
        )

        // 5. محاولة الضغط على الزر وتسجيل النتيجة
        button?.let {
            val buttonKey = "button_${it.text ?: it.contentDescription}"
            val clicked = NodeActionExecutor.click(it)
            
            if (clicked) {
                // ✅ سجل نجاح الضغط على هذا الزر
                ReinforcementMemory.recordSuccess(buttonKey)
            } else {
                // ❌ سجل فشل التفاعل مع هذا الزر
                ReinforcementMemory.recordFailure(buttonKey)
            }
        }

        return CommandResult(true)
    }
}
