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
            ActionType.OPEN_APP -> openApp(command.target) // تستدعي الدالة الموحدة بالأسفل
            ActionType.OPEN_URL -> openUrl(command.target)
            ActionType.NAVIGATE_BACK -> navigateBack()
            else -> Log.w("AIRI_CONTROL", "أمر غير مدعوم حالياً.")
        }
    }

    /**
     * دالة موحدة لفتح التطبيق بناءً على اسم الحزمة (Package Name).
     */
    fun openApp(packageName: String?) {
        if (packageName.isNullOrEmpty()) {
            Log.e("AIRI_CONTROL", "اسم الحزمة فارغ أو غير موجود.")
            return
        }

        Log.d("AIRI_CONTROL", "محاولة فتح التطبيق: $packageName")
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Log.e("AIRI_CONTROL", "لم يتم العثور على تطبيق بالحزمة: $packageName")
            }
        } catch (e: Exception) {
            Log.e("AIRI_CONTROL", "خطأ أثناء محاولة فتح التطبيق: ${e.message}")
        }
    }

    private fun openUrl(url: String?) {
        url?.let {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("AIRI_CONTROL", "فشل فتح الرابط: ${e.message}")
            }
        }
    }

    private fun navigateBack() {
        AiriAccessibilityService.getInstance()?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        )
    }

    /**
     * تنفيذ أمر نظام عام بناءً على نص الأمر.
     */
    fun executeCommand(command: String) {
        Log.d("AIRI_CONTROL", "Executing system command: $command")
        // سيتم إضافة المنطق الخاص بالصوت والسطوع لاحقاً
    }
}
