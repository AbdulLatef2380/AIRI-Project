package com.airi.assistant.domain.permission

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.airi.assistant.accessibility.service.AiriAccessibilityService

/**
 * Readiness contract for device automation.
 *
 * This is deliberately fail-closed: a declared service is not considered ready
 * until Android reports it enabled and the service has actually connected.
 */
object AccessibilityReadiness {
    enum class Status {
        NOT_INSTALLED,
        DISABLED,
        ENABLED_NOT_CONNECTED,
        READY,
    }

    enum class NextAction {
        OPEN_ACCESSIBILITY_SETTINGS,
        WAIT_FOR_SERVICE_CONNECTION,
        NONE,
    }

    data class Snapshot(
        val status: Status,
        val installed: Boolean,
        val enabled: Boolean,
        val connected: Boolean,
        val agentAdmitted: Boolean,
        val nextAction: NextAction,
    ) {
        val isReady: Boolean get() = status == Status.READY
    }

    fun inspect(context: Context): Snapshot {
        val appContext = context.applicationContext
        val installed = isServiceInstalled(appContext)
        val enabled = AccessibilityServiceState.isEnabled(appContext)
        val connected = AiriAccessibilityService.isConnected
        val status = deriveStatus(installed, enabled, connected)
        return Snapshot(
            status = status,
            installed = installed,
            enabled = enabled,
            connected = connected,
            agentAdmitted = status == Status.READY,
            nextAction = when (status) {
                Status.NOT_INSTALLED,
                Status.DISABLED -> NextAction.OPEN_ACCESSIBILITY_SETTINGS
                Status.ENABLED_NOT_CONNECTED -> NextAction.WAIT_FOR_SERVICE_CONNECTION
                Status.READY -> NextAction.NONE
            },
        )
    }

    internal fun deriveStatus(installed: Boolean, enabled: Boolean, connected: Boolean): Status = when {
        !installed -> Status.NOT_INSTALLED
        !enabled -> Status.DISABLED
        !connected -> Status.ENABLED_NOT_CONNECTED
        else -> Status.READY
    }

    private fun isServiceInstalled(context: Context): Boolean = runCatching {
        val intent = Intent(AccessibilityService.SERVICE_INTERFACE)
        context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
            .any { it.serviceInfo.packageName == context.packageName &&
                it.serviceInfo.name == AiriAccessibilityService::class.java.name }
    }.getOrDefault(false)
}
