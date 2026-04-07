package com.airi.assistant.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AiriAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AiriAccessibilityService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle accessibility events
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenContextHolder.serviceInstance = null
        Log.d(TAG, "Accessibility service destroyed")
    }
}
