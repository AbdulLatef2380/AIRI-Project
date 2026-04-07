package com.airi.assistant.accessibility.service

import android.accessibilityservice.AccessibilityService

object ScreenContextHolder {
    @Volatile
    var serviceInstance: AccessibilityService? = null

    val isConnected: Boolean
        get() = serviceInstance != null
}
