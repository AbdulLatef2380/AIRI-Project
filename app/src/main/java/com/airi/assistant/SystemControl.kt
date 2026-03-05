package com.airi.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.accessibilityservice.AccessibilityService
import com.airi.assistant.accessibility.ScreenContextHolder

/**
 * تمثيل للأوامر التي يمكن لـ AIRI تنفيذها.
 */
data class AiriCommand(
    val action: ActionType,
    val target: String? = null,
    val requiresConfirmation: Boolean = true
)

enum class ActionType {
    OPEN_APP,
    VOLUME_UP,
    VOLUME_DOWN,
    MEDIA_PLAY_PAUSE,
    NAVIGATE_BACK,
    OPEN_URL,
    UNKNOWN
}

/**
 * مدير التحكم بالنظام (Execution Layer).
 */
class SystemControlManager(private val context: Context) {

    fun execute(command: AiriCommand) {
        Log.d("AIRI_CONTROL", "Executing: ${command.action} -> ${command.target}")

        when (command.action) {
            ActionType.OPEN_APP -> openApp(command.target)
            ActionType.OPEN_URL -> openUrl(command.target)
            ActionType.NAVIGATE_BACK -> navigateBack()
            else -> Log.w("AIRI_CONTROL", "Unsupported action.")
        }
    }

    private fun openApp(packageName: String?) {
        if (packageName.isNullOrEmpty()) {
            Log.e("AIRI_CONTROL", "Package name is null or empty.")
            return
        }

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Log.e("AIRI_CONTROL", "App not found: $packageName")
            }
        } catch (e: Exception) {
            Log.e("AIRI_CONTROL", "Open app failed: ${e.message}")
        }
    }

    private fun openUrl(url: String?) {
        if (url.isNullOrEmpty()) return

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AIRI_CONTROL", "Open URL failed: ${e.message}")
        }
    }

    /**
     * ✅ الربط الصحيح مع Accessibility Service
     */
    private fun navigateBack() {
        val service = ScreenContextHolder.serviceInstance

        if (service == null) {
            Log.e("AIRI_CONTROL", "AccessibilityService not connected.")
            return
        }

        service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
    }

    fun executeCommand(command: String) {
        Log.d("AIRI_CONTROL", "Executing raw command: $command")
    }
}
