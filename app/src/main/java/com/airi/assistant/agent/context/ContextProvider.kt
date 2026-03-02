package com.airi.assistant.agent.context

import android.accessibilityservice.AccessibilityService

object ContextProvider {

    fun getAppContext(service: AccessibilityService): String {
        val root = service.rootInActiveWindow ?: return "unknown_app"
        val pkg = root.packageName?.toString() ?: "unknown_pkg"
        val className = root.className?.toString() ?: "unknown_screen"
        return "${pkg}_${className}"
    }
}
