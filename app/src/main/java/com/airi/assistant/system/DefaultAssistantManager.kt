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
}
