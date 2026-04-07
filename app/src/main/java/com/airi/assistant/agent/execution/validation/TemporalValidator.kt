package com.airi.assistant.agent.execution.validation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay

object TemporalValidator {

    private const val TAG = "TemporalValidator"
    private const val VALIDATION_DELAY_MS = 400L
    private const val MAX_WAIT_MS = 2000L
    private const val POLL_INTERVAL_MS = 200L

    suspend fun validateAction(service: AccessibilityService): Boolean {
        delay(VALIDATION_DELAY_MS)
        val windowAvailable = service.rootInActiveWindow != null
        Log.d(TAG, "Action validation: window available = $windowAvailable")
        return windowAvailable
    }

    suspend fun waitForWindowChange(service: AccessibilityService, timeoutMs: Long = MAX_WAIT_MS): Boolean {
        val startWindow = service.rootInActiveWindow?.packageName?.toString()
        var elapsed = 0L

        while (elapsed < timeoutMs) {
            delay(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS
            val currentWindow = service.rootInActiveWindow?.packageName?.toString()
            if (currentWindow != null && currentWindow != startWindow) {
                Log.d(TAG, "Window changed from $startWindow to $currentWindow after ${elapsed}ms")
                return true
            }
        }

        Log.w(TAG, "Window did not change within ${timeoutMs}ms")
        return false
    }

    suspend fun waitForCondition(
        timeoutMs: Long = MAX_WAIT_MS,
        condition: suspend () -> Boolean
    ): Boolean {
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (condition()) return true
            delay(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS
        }
        return false
    }
}
