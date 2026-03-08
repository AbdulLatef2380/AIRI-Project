package com.airi.assistant.brain

import com.airi.assistant.accessibility.AIRIAccessibilityService
import com.airi.assistant.accessibility.ActionExecutor

object IntentEngine {

    fun resolve(screen: String): String? {
        val lower = screen.lowercase()

        if (lower.contains("first")) return "CLICK_FIRST"
        if (lower.contains("second") || lower.contains("next")) return "CLICK_INDEX:1"
        if (lower.contains("back") || lower.contains("رجوع")) return "BACK"

        return when {
            lower.contains("search") -> "CLICK:search"
            lower.contains("subscribe") -> "CLICK:subscribe"
            lower.contains("play") -> "CLICK:play"
            else -> null
        }
    }

    fun execute(command: String) {
        val service = AIRIAccessibilityService.instance ?: return

        when {
            command == "CLICK_FIRST" -> {
                ActionExecutor.clickFirst(service)
            }

            command.startsWith("CLICK_INDEX:") -> {
                val index = command.substringAfter(":").toIntOrNull() ?: 0
                ActionExecutor.clickByIndex(service, index)
            }

            command.startsWith("CLICK:") -> {
                val target = command.substringAfter(":")
                service.executeCommand("اضغط $target")
            }

            command.startsWith("TYPE:") -> {
                val target = command.substringAfter(":")
                service.executeCommand("اكتب $target")
            }

            command == "BACK" -> {
                service.executeCommand("رجوع")
            }
        }
    }
}
