package com.airi.assistant.agent.planning

import android.util.Log
import com.airi.assistant.accessibility.executor.ActionExecutor
import com.airi.assistant.accessibility.service.AiriAccessibilityService
import com.airi.assistant.accessibility.service.ScreenContextHolder
import com.airi.assistant.core.intent.IntentType

/**
 * Intent Resolution Engine — converts screen text or descriptions into executable intents.
 */
object IntentEngine {

    private const val TAG = "IntentEngine"

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
            lower.contains("search") -> AiriIntent(IntentType.CLICK, target = "search")
            lower.contains("subscribe") -> AiriIntent(IntentType.CLICK, target = "subscribe")
            lower.contains("play") -> AiriIntent(IntentType.CLICK, target = "play")
            else -> null
        }
    }

    /**
     * Execute an intent via the Accessibility layer.
     * Falls back gracefully if the service is not connected.
     */
    fun execute(intent: AiriIntent) {

        // Use AiriAccessibilityService.instance (set on service connect)
        val service: AiriAccessibilityService? = AiriAccessibilityService.instance

        if (service == null) {
            Log.w(TAG, "Accessibility service not connected — cannot execute intent: ${intent.type}")
            return
        }

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

            else -> {
                Log.d(TAG, "Intent type ${intent.type} not handled in IntentEngine.execute()")
            }
        }
    }
}
