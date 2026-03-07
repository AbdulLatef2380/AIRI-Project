package com.airi.assistant.brain

import com.airi.assistant.accessibility.AIRIAccessibilityService

object IntentEngine {

    fun resolve(screen: String): String? {

        val lower = screen.lowercase()

        // مثال: فتح البحث
        if (lower.contains("search")) {
            return "click:Search"
        }

        // مثال: زر الاشتراك
        if (lower.contains("subscribe")) {
            return "click:Subscribe"
        }

        // مثال: زر التشغيل
        if (lower.contains("play")) {
            return "click:Play"
        }

        return null
    }

    fun execute(command: String) {

        val service = AIRIAccessibilityService.instance ?: return

        when {

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
