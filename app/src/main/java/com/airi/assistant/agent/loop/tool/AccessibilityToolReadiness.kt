package com.airi.assistant.agent.loop.tool

import com.airi.assistant.domain.permission.AccessibilityReadiness

/** Projects the live Accessibility lifecycle into the AgentLoop catalog contract. */
object AccessibilityToolReadiness {
    fun override(snapshot: AccessibilityReadiness.Snapshot): RuntimeToolCatalog.StatusOverride? =
        if (snapshot.isReady) {
            null
        } else {
            RuntimeToolCatalog.StatusOverride(
                readiness = RuntimeToolContract.Readiness.PERMISSION_REQUIRED,
                reason = when (snapshot.status) {
                    AccessibilityReadiness.Status.NOT_INSTALLED,
                    AccessibilityReadiness.Status.DISABLED ->
                        "AIRI Accessibility Service is not enabled. Enable it in Android Accessibility settings."
                    AccessibilityReadiness.Status.ENABLED_NOT_CONNECTED ->
                        "AIRI Accessibility Service is enabled but not connected. Reopen AIRI and try again."
                    AccessibilityReadiness.Status.READY -> "",
                },
                nextAction = when (snapshot.status) {
                    AccessibilityReadiness.Status.NOT_INSTALLED,
                    AccessibilityReadiness.Status.DISABLED -> "enable_accessibility"
                    AccessibilityReadiness.Status.ENABLED_NOT_CONNECTED -> "wait_for_accessibility_connection"
                    AccessibilityReadiness.Status.READY -> null
                },
            )
        }
}
