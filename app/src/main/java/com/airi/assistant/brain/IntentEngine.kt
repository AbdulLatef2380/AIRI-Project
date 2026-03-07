package com.airi.assistant.brain

import com.airi.assistant.accessibility.AIRIAccessibilityService
import com.airi.assistant.accessibility.ActionExecutor

object IntentEngine {

    /**
     * تحليل محتوى الشاشة وتحويله إلى "أمر" مفهوم للنظام
     */
    fun resolve(screen: String): String? {
        val lower = screen.lowercase()

        // 🧠 منطق التعامل مع الترتيب الهيكلي (جديد)
        if (lower.contains("first")) {
            return "click_first"
        }

        if (lower.contains("second") || lower.contains("next")) {
            return "click_index:1"
        }

        // 🔍 منطق البحث عن الكلمات المفتاحية
        return when {
            lower.contains("search") -> "click:Search"
            lower.contains("subscribe") -> "click:Subscribe"
            lower.contains("play") -> "click:Play"
            else -> null
        }
    }

    /**
     * تنفيذ الأمر الناتج عن عملية التحليل
     */
    fun execute(command: String) {
        val service = AIRIAccessibilityService.instance ?: return

        when {
            // ✅ تنفيذ الضغط على العنصر الأول
            command == "click_first" -> {
                ActionExecutor.clickFirst(service)
            }

            // ✅ تنفيذ الضغط بناءً على الفهرس (Index)
            command.startsWith("click_index:") -> {
                val index = command
                    .removePrefix("click_index:")
                    .toIntOrNull() ?: 0
                ActionExecutor.clickByIndex(service, index)
            }

            // تنفيذ الأوامر النصية التقليدية عبر executeCommand
            command.startsWith("click:") -> {
                val text = command.removePrefix("click:")
                service.executeCommand("اضغط $text")
            }

            command.startsWith("type:") -> {
                val text = command.removePrefix("type:")
                service.executeCommand("اكتب $text")
            }
        }
    }
}
