package com.airi.assistant.domain.permission

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Reads Android's recorded enablement state for AIRI's accessibility service. */
object AccessibilityServiceState {
    fun isEnabled(context: Context): Boolean = runCatching {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return@runCatching false
        containsEnabledPackage(enabledServices, context.packageName)
    }.getOrDefault(false)

    internal fun containsEnabledPackage(enabledServices: String?, packageName: String): Boolean =
        enabledServices
            ?.split(':')
            ?.any { component ->
                ComponentName.unflattenFromString(component)?.packageName == packageName
            }
            ?: false
}
