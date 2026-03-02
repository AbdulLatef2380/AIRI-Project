package com.airi.assistant.agent.command

import android.accessibilityservice.AccessibilityService
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.agent.context.ContextProvider // 🔥 استيراد موفر السياق
import com.airi.assistant.agent.node.NodeActionExecutor
import com.airi.assistant.agent.node.NodeScanner
import com.airi.assistant.agent.node.SemanticRanker
import com.airi.assistant.agent.reinforcement.ReinforcementMemory

object AccessibilityCommandBridge {

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
     * 🔥 التنفيذ الاحترافي: إدخال نص ذكي مع وعي كامل بالسياق والتعلم التكيفي
     */
    fun typeText(text: String): CommandResult {

        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")

        // 1️⃣ استخراج سياق التطبيق والشاشة الحالية (مثال: com.whatsapp_ChatActivity)
        val context = ContextProvider.getAppContext(service)

        // 2️⃣ مسح الشاشة لجمع العقد
        val nodes = NodeScanner.collectAllNodes(root)

        // 3️⃣ اختيار أفضل حقل إدخال بناءً على السياق الحالي
        val editable = SemanticRanker.rankEditableNodes(nodes, context)
            ?: return CommandResult(false, "No suitable editable field found in context: $context")

        val editableKey = "editable_${editable.hintText ?: editable.viewIdResourceName}"

        // 4️⃣ محاولة إدخال النص وتسجيل النتيجة في ذاكرة السياق
        val typed = NodeActionExecutor.typeText(editable, text)
        
        if (typed) {
            ReinforcementMemory.recordSuccess(context, editableKey)
        } else {
            ReinforcementMemory.recordFailure(context, editableKey)
            return CommandResult(false, "Failed to type in $context")
        }

        // 5️⃣ البحث الدلالي عن زر الإجراء بناءً على السياق الحالي
        val button = SemanticRanker.rankActionButton(
            nodes,
            listOf("send", "search", "ok", "submit", "إرسال", "بحث", "تم"),
            context
        )

        // 6️⃣ محاولة الضغط على الزر وتسجيل الخبرة المكتسبة لهذا التطبيق تحديداً
        button?.let {
            val buttonKey = "button_${it.text ?: it.contentDescription ?: it.viewIdResourceName}"
            val clicked = NodeActionExecutor.click(it)
            
            if (clicked) {
                // ✅ تعلم أن هذا الزر ناجح في هذا السياق
                ReinforcementMemory.recordSuccess(context, buttonKey)
            } else {
                // ❌ تعلم أن هذا الزر فشل في هذا السياق (تجنبه مستقبلاً)
                ReinforcementMemory.recordFailure(context, buttonKey)
            }
        }

        return CommandResult(true)
    }
}
