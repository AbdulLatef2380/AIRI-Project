package com.airi.assistant.brain

import com.airi.assistant.accessibility.AIRIAccessibilityService
import com.airi.assistant.accessibility.ActionExecutor

enum class IntentType {
    CLICK, CLICK_FIRST, CLICK_INDEX, TYPE, BACK
}

data class AiriIntent(
    val type: IntentType,
    val target: String? = null,
    val index: Int? = 0
)

object IntentEngine {

    fun resolve(screen: String): AiriIntent? {
        val lower = screen.lowercase()

        if (lower.contains("first")) {
            return AiriIntent(IntentType.CLICK_FIRST)
        }

        if (lower.contains("second") || lower.contains("next")) {
            return AiriIntent(IntentType.CLICK_INDEX, index = 1)
        }

        if (lower.contains("back") || lower.contains("رجوع")) {
            return AiriIntent(IntentType.BACK)
        }

        return when {
            lower.contains("search") -> AiriIntent(IntentType.CLICK, "search")
            lower.contains("subscribe") -> AiriIntent(IntentType.CLICK, "subscribe")
            lower.contains("play") -> AiriIntent(IntentType.CLICK, "play")
            else -> null
        }
    }

    fun execute(AiriIntent: AiriIntent) {
        val service = AIRIAccessibilityService.instance ?: return

        when (AiriIntent.type) {
            IntentType.CLICK_FIRST -> {
                ActionExecutor.clickFirst(service)
            }

            IntentType.CLICK_INDEX -> {
                ActionExecutor.clickByIndex(service, intent.index ?: 0)
            }

            IntentType.CLICK -> {
                val target = AiriIntent.target ?: return
                service.executeCommand("اضغط $target")
            }

            IntentType.TYPE -> {
                val text = AiriIntent.target ?: return
                service.executeCommand("اكتب $text")
            }

            IntentType.BACK -> {
                service.executeCommand("رجوع")
            }

            else -> {}
        }
    }
}
