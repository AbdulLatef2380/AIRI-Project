package com.airi.assistant.brain

import com.airi.assistant.accessibility.AIRIAccessibilityService
import com.airi.assistant.accessibility.ActionExecutor

enum class IntentType {
    CLICK, CLICK_FIRST, CLICK_INDEX, TYPE, BACK
}

data class Intent(
    val type: IntentType,
    val target: String? = null,
    val index: Int? = 0
)

object IntentEngine {

    fun resolve(screen: String): Intent? {
        val lower = screen.lowercase()

        if (lower.contains("first")) {
            return Intent(IntentType.CLICK_FIRST)
        }

        if (lower.contains("second") || lower.contains("next")) {
            return Intent(IntentType.CLICK_INDEX, index = 1)
        }

        if (lower.contains("back") || lower.contains("رجوع")) {
            return Intent(IntentType.BACK)
        }

        return when {
            lower.contains("search") -> Intent(IntentType.CLICK, "search")
            lower.contains("subscribe") -> Intent(IntentType.CLICK, "subscribe")
            lower.contains("play") -> Intent(IntentType.CLICK, "play")
            else -> null
        }
    }

    fun execute(intent: Intent) {
        val service = AIRIAccessibilityService.instance ?: return

        when (intent.type) {
            IntentType.CLICK_FIRST -> {
                ActionExecutor.clickFirst(service)
            }

            IntentType.CLICK_INDEX -> {
                ActionExecutor.clickByIndex(service, intent.index ?: 0)
            }

            IntentType.CLICK -> {
                val target = intent.target ?: return
                service.executeCommand("اضغط $target")
            }

            IntentType.TYPE -> {
                val text = intent.target ?: return
                service.executeCommand("اكتب $text")
            }

            IntentType.BACK -> {
                service.executeCommand("رجوع")
            }

            else -> {}
        }
    }
}
