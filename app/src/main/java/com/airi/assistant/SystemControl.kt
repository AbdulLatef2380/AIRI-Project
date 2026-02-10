package com.airi.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

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
 * مدير التحكم بالنظام (The Hands).
 */
class SystemControlManager(private val context: Context) {

    /**
     * تنفيذ الأمر بعد التحقق والموافقة.
     */
    fun execute(command: AiriCommand) {
        Log.d("AIRI_CONTROL", "تنفيذ الأمر: ${command.action} على ${command.target}")
        
        when (command.action) {
            ActionType.OPEN_APP -> openApp(command.target)
            ActionType.OPEN_URL -> openUrl(command.target)
            ActionType.NAVIGATE_BACK -> navigateBack()
            // سيتم إضافة باقي الأوامر هنا
            else -> Log.w("AIRI_CONTROL", "أمر غير مدعوم حالياً.")
        }
    }

    private fun openApp(packageName: String?) {
        packageName?.let {
            val intent = context.packageManager.getLaunchIntentForPackage(it)
            intent?.let { i -> context.startActivity(i) }
        }
    }

    private fun openUrl(url: String?) {
        url?.let {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun navigateBack() {
        AiriAccessibilityService.getInstance()?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        )
    }
}
