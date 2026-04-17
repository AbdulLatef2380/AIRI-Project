package com.airi.assistant.reporting

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.airi.assistant.BuildConfig

class DeviceInfoProvider(private val context: Context) {

    fun deviceInfo(): String {
        val memory = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            val availableMb = info.availMem / (1024 * 1024)
            val totalMb = info.totalMem / (1024 * 1024)
            "$availableMb MB available / $totalMb MB total"
        }.getOrElse { "Unavailable" }

        return buildString {
            appendLine("Device model: ${Build.MODEL}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Available memory: $memory")
        }
    }

    fun appInfo(currentScreenName: String, lastUserAction: String?): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: BuildConfig.VERSION_NAME
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode?.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toString()
        } ?: BuildConfig.VERSION_CODE.toString()

        return buildString {
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
            appendLine("Debug build: ${BuildConfig.DEBUG}")
            appendLine("Package: ${BuildConfig.APPLICATION_ID}")
            appendLine("Current screen name: $currentScreenName")
            appendLine("Last user action: ${lastUserAction?.takeIf { it.isNotBlank() } ?: "Unavailable"}")
        }
    }
}