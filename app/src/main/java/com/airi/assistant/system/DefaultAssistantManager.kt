package com.airi.assistant.system

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.airi.assistant.domain.logging.LoggingService

object DefaultAssistantManager {

    private const val TAG               = "DefaultAssistantManager"
    const val REQUEST_ROLE_CODE         = 1001

    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        return false
    }

    fun isAssistantRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        }
        return false
    }

    fun requestDefaultAssistant(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                LoggingService.warn(TAG, "ROLE_ASSISTANT not available on this device")
                openAssistantSettings(activity)
                return
            }
            if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                LoggingService.info(TAG, "AIRI is already the default assistant")
                return
            }
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            activity.startActivityForResult(intent, REQUEST_ROLE_CODE)
        } else {
            openAssistantSettings(activity)
        }
    }

    fun openAssistantSettings(context: Context) {
        val intents = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent("com.android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        // Last resort: open main settings
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Opens the MIUI/HyperOS autostart settings for this app.
     *
     * Xiaomi/Redmi devices running MIUI 12+ or HyperOS restrict background
     * execution by default. The foreground services (LiveVoiceService,
     * HotwordService, ModelDownloadService) require the app to be whitelisted
     * in MIUI's autostart manager, otherwise they are killed on screen-off.
     *
     * Falls back gracefully if the intent is not available (non-Xiaomi devices).
     */
    fun openMiuiAutostartSettings(context: Context) {
        if (Build.MANUFACTURER.lowercase() != "xiaomi") return
        val intents = listOf(
            // MIUI 12 / HyperOS
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            },
            // MIUI 10/11
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            },
            // Generic battery optimization (works on most OEMs)
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent); return } catch (_: Exception) {}
        }
    }

    /**
     * Returns true if this device is a Xiaomi/Redmi/POCO device running MIUI or HyperOS.
     * Use this to conditionally show MIUI-specific onboarding prompts.
     */
    fun isMiuiDevice(): Boolean = Build.MANUFACTURER.lowercase() == "xiaomi"
}
