package com.airi.assistant.resources

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.airi.assistant.R
import java.util.concurrent.atomic.AtomicLong

/**
 * User-owned resource budget. Storage is a real filesystem quota for AIRI's
 * private files directory; RAM/CPU are runtime guidance limits, not fake OS
 * reservations (Android does not allow an app to reserve arbitrary RAM/CPU).
 */
class ResourceBudgetManager(private val context: Context) {
    companion object {
        const val MIN_STORAGE_GB = 1
        const val MAX_STORAGE_GB = 8
        private const val PREFS = "airi_resource_budget"
        private const val KEY_STORAGE_GB = "storage_budget_gb"
        private const val KEY_RAM_MB = "ram_budget_mb"
        private const val KEY_CPU_PERCENT = "cpu_budget_percent"
        private const val CHANNEL_ID = "airi_resource_alerts"
        private const val NOTIFICATION_ID = 4107
    }

    data class Snapshot(
        val storageBudgetBytes: Long,
        val storageUsedBytes: Long,
        val storageFreeBytes: Long,
        val storagePercent: Int,
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val ramBudgetBytes: Long,
        val cpuCores: Int,
        val cpuBudgetPercent: Int,
        val storageWarning: Boolean,
        val ramWarning: Boolean
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lastNotificationAt = AtomicLong(0L)

    fun storageBudgetGb(): Int = prefs.getInt(KEY_STORAGE_GB, 2).coerceIn(MIN_STORAGE_GB, MAX_STORAGE_GB)
    fun ramBudgetMb(): Int = prefs.getInt(KEY_RAM_MB, recommendedRamBudgetMb())
    fun cpuBudgetPercent(): Int = prefs.getInt(KEY_CPU_PERCENT, 100).coerceIn(25, 100)

    fun setStorageBudgetGb(value: Int) { prefs.edit().putInt(KEY_STORAGE_GB, value.coerceIn(MIN_STORAGE_GB, MAX_STORAGE_GB)).apply() }
    fun setRamBudgetMb(value: Int) { prefs.edit().putInt(KEY_RAM_MB, value.coerceAtLeast(512)).apply() }
    fun setCpuBudgetPercent(value: Int) { prefs.edit().putInt(KEY_CPU_PERCENT, value.coerceIn(25, 100)).apply() }

    fun snapshot(): Snapshot {
        val stats = StatFs(context.filesDir.absolutePath)
        val block = stats.blockSizeLong
        val used = (stats.totalBytes - stats.availableBytes).coerceAtLeast(0L)
        val budget = storageBudgetGb().toLong() * 1024L * 1024L * 1024L
        val percent = ((used.toDouble() / budget.coerceAtLeast(1L)) * 100.0).toInt().coerceAtLeast(0)
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        val totalRam = memory.totalMem
        val availableRam = memory.availMem
        val ramBudget = ramBudgetMb().toLong() * 1024L * 1024L
        return Snapshot(
            storageBudgetBytes = budget,
            storageUsedBytes = used,
            storageFreeBytes = (budget - used).coerceAtLeast(0L),
            storagePercent = percent,
            totalRamBytes = totalRam,
            availableRamBytes = availableRam,
            ramBudgetBytes = ramBudget,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            cpuBudgetPercent = cpuBudgetPercent(),
            storageWarning = percent >= 85,
            ramWarning = availableRam < ramBudget / 5
        )
    }

    fun notifyIfNeeded(snapshot: Snapshot = snapshot()) {
        if (!snapshot.storageWarning && !snapshot.ramWarning) return
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt.get() < 6 * 60 * 60 * 1000L) return
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AIRI resource alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val message = when {
            snapshot.storageWarning -> "AIRI storage is ${snapshot.storagePercent}% full. Delete unused models or increase the storage budget."
            else -> "Available RAM is low. Reduce the RAM budget or unload the current model."
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("AIRI resource warning")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
        )
        lastNotificationAt.set(now)
    }

    private fun recommendedRamBudgetMb(): Int {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        return (memory.totalMem / (1024L * 1024L) / 2L).toInt().coerceIn(512, 8192)
    }
}

fun Long.asResourceSize(): String {
    if (this < 1024L * 1024L) return "${this / 1024L} KB"
    val gb = this.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.2f GB".format(java.util.Locale.US, gb)
    else "%.0f MB".format(java.util.Locale.US, gb * 1024.0)
}
