package com.airi.assistant.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AiriAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AiriAccessibilityService"

        /** Singleton instance set when the service connects */
        @Volatile
        var instance: AiriAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenContextHolder.serviceInstance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle accessibility events if needed
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        ScreenContextHolder.serviceInstance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Executes a high-level text command routed from the cognitive layer.
     * Currently logs the command; extend here to dispatch real actions.
     */
    fun executeCommand(command: String) {
        Log.d(TAG, "executeCommand: $command")
        // Route command to SystemControl or ActionExecutor as needed
    }
}
