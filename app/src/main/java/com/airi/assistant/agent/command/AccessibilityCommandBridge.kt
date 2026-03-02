package com.airi.assistant.agent.command

import com.airi.assistant.accessibility.ScreenContextHolder

object AccessibilityCommandBridge {

    fun launchApp(): CommandResult {
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            service.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            )
            CommandResult(true)
        } catch (e: Exception) {
            CommandResult(false, e.message)
        }
    }

    fun performBack(): CommandResult {
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            service.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            CommandResult(true)
        } catch (e: Exception) {
            CommandResult(false, e.message)
        }
    }

    fun typeText(text: String): CommandResult {
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")

        // هنا تحتاج منطق حقيقي لإيجاد editable node
        return CommandResult(true)
    }
}
