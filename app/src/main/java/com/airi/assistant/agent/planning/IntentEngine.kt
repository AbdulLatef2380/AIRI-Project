package com.airi.assistant.agent.planning

import com.airi.assistant.accessibility.executor.ActionExecutor
import com.airi.assistant.accessibility.service.AIRIAccessibilityService
import com.airi.assistant.core.intent.IntentType

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

            lower.contains("search") ->
                AiriIntent(IntentType.CLICK, "search")

            lower.contains("subscribe") ->
                AiriIntent(IntentType.CLICK, "subscribe")

            lower.contains("play") ->
                AiriIntent(IntentType.CLICK, "play")

            else -> null
        }
    }

    fun execute(intent: AiriIntent) {

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
        }
    }
}
