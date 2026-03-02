package com.airi.assistant.agent.command

import android.accessibilityservice.AccessibilityService
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.agent.context.ContextProvider
import com.airi.assistant.agent.node.NodeActionExecutor
import com.airi.assistant.agent.node.NodeScanner
import com.airi.assistant.agent.node.SemanticRanker
import com.airi.assistant.agent.reinforcement.ReinforcementMemory
import com.airi.assistant.agent.validation.TemporalValidator // 🔥 استيراد المدقق الزمني

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
     * 🔥 التنفيذ الفائق: إدخال نص + تحقق من تغير الحالة (Outcome Verification)
     * ملاحظة: تم تحويل الدالة إلى suspend لدعم الانتظار الزمني للتحقق.
     */
    suspend fun typeText(text: String): CommandResult {

        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")

        val context = ContextProvider.getAppContext(service)
        val nodes = NodeScanner.collectAllNodes(root)

        // 1️⃣ اختيار حقل الإدخال
        val editable = SemanticRanker.rankEditableNodes(nodes, context)
            ?: return CommandResult(false, "No suitable field found")

        val editableKey = "editable_${editable.hintText ?: editable.viewIdResourceName}"

        // 2️⃣ تنفيذ الكتابة والتحقق من تغير الواجهة (هل ظهر النص فعلاً؟)
        NodeActionExecutor.typeText(editable, text)
        
        val typingConfirmed = TemporalValidator.validateAction(service)
        if (typingConfirmed) {
            ReinforcementMemory.recordSuccess(context, editableKey)
        } else {
            ReinforcementMemory.recordFailure(context, editableKey)
            // إذا لم يتغير شيء بعد الكتابة، قد يكون الحقل محمياً أو معطلاً
            return CommandResult(false, "UI state didn't change after typing")
        }

        // 3️⃣ البحث عن زر الإجراء (إرسال/بحث)
        val button = SemanticRanker.rankActionButton(
            nodes,
            listOf("send", "search", "ok", "submit", "إرسال", "بحث", "تم"),
            context
        )

        // 4️⃣ الضغط على الزر والتحقق من النتيجة النهائية (هل تم إرسال الرسالة/تغيرت الصفحة؟)
        button?.let {
            val buttonKey = "button_${it.text ?: it.contentDescription ?: it.viewIdResourceName}"
            
            NodeActionExecutor.click(it)

            // الانتظار والتحقق من حدوث تغيير هيكلي في الشاشة
            val actionConfirmed = TemporalValidator.validateAction(service)
            
            if (actionConfirmed) {
                // ✅ تغيير حقيقي تم رصده (الرسالة أرسلت أو الصفحة تغيرت)
                ReinforcementMemory.recordSuccess(context, buttonKey)
            } else {
                // ❌ ضغطنا الزر ولكن لم يحدث شيء (ربما الزر وهمي أو يتطلب ضغطة مطولة)
                ReinforcementMemory.recordFailure(context, buttonKey)
                return CommandResult(false, "Action executed but UI state remains identical")
            }
        }

        return CommandResult(true)
    }
}
