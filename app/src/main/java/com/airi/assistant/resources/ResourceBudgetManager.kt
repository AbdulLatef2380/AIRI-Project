package com.airi.assistant.resources

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airi.assistant.R
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Pure safety boundaries shared by UI, workers, and future download preflight. */
object ResourceBudgetPolicy {
    const val STORAGE_WARNING_PERCENT = 85
    const val DEVICE_FREE_WARNING_PERCENT = 12
    const val RAM_WARNING_FRACTION = 5L
    const val MIN_RAM_WARNING_BYTES = 256L * 1024L * 1024L
    fun storageWarning(percent: Int) = percent >= STORAGE_WARNING_PERCENT
    fun deviceStorageWarning(free: Long, total: Long) = total > 0 && free * 100 / total <= DEVICE_FREE_WARNING_PERCENT
    fun ramWarning(availableBytes: Long, budgetBytes: Long) = availableBytes < maxOf(MIN_RAM_WARNING_BYTES, budgetBytes / RAM_WARNING_FRACTION)
    fun notificationMessage(storageWarning: Boolean, ramWarning: Boolean, storagePercent: Int): String = when {
        storageWarning && ramWarning -> "AIRI storage is $storagePercent% full and available RAM is low."
        storageWarning -> "AIRI storage is $storagePercent% full."
        else -> "Available RAM is low."
    }
}

enum class ResourceCategory(val label: String) {
    MODELS("Models"), MEDIA("Media"), VOICE("Voice"), MEMORY("Memory"), KNOWLEDGE("Knowledge"),
    SKILLS("Skills"), CONVERSATIONS("Conversations"), CACHE("Cache"), DOWNLOADS("Downloads"),
    LOGS("Logs"), BACKUP("Backup / Export"), OTHER("Other")
}

data class ResourceEntry(
    val resourceId: String,
    val category: ResourceCategory,
    val ownerSubsystem: String,
    val path: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val lastUsedAt: Long,
    val removable: Boolean,
    val rebuildable: Boolean,
    val state: String = "present"
)

data class ResourceCategoryTotal(val category: ResourceCategory, val sizeBytes: Long, val itemCount: Int, val rebuildableBytes: Long)

/** Registry + filesystem reconciliation for app-owned data only. No broad storage permission. */
class ResourceRegistry(private val context: Context) {
    fun reconcile(): List<ResourceEntry> = roots().flatMap { (owner, root) ->
        if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.map { file ->
            val relative = file.relativeToOrNull(root)?.path.orEmpty().lowercase(Locale.ROOT)
            val category = classify(relative, owner)
            ResourceEntry(
                resourceId = "${owner}:${file.absolutePath}", category = category, ownerSubsystem = owner,
                path = file.absolutePath, sizeBytes = file.length().coerceAtLeast(0),
                createdAt = file.lastModified(), lastUsedAt = file.lastModified(), removable = category != ResourceCategory.OTHER,
                rebuildable = category in setOf(ResourceCategory.CACHE, ResourceCategory.DOWNLOADS, ResourceCategory.KNOWLEDGE)
            )
        }.toList()
    }
    fun totals(): List<ResourceCategoryTotal> = reconcile().groupBy { it.category }.map { (category, entries) ->
        ResourceCategoryTotal(category, entries.sumOf { it.sizeBytes }, entries.size, entries.filter { it.rebuildable }.sumOf { it.sizeBytes })
    }.sortedByDescending { it.sizeBytes }
    private fun roots() = listOf(
        "app" to context.filesDir, "no-backup" to context.noBackupFilesDir,
        "external" to context.getExternalFilesDir(null)
    ).filter { it.second != null }.map { it.first to it.second!! }
    private fun classify(path: String, owner: String) = when {
        path.contains("model") || path.endsWith(".gguf") || path.endsWith(".onnx") -> ResourceCategory.MODELS
        path.contains("voice") || path.contains("vosk") || path.endsWith(".wav") -> ResourceCategory.VOICE
        path.contains("attachment") || path.contains("image") || path.contains("video") || path.endsWith(".mp4") -> ResourceCategory.MEDIA
        path.contains("memory") || path.contains("embedding") -> ResourceCategory.MEMORY
        path.contains("knowledge") || path.contains("rag") || path.contains("index") -> ResourceCategory.KNOWLEDGE
        path.contains("skill") -> ResourceCategory.SKILLS
        path.contains("conversation") || path.contains("chat") || path.contains("session") || path.endsWith(".db") -> ResourceCategory.CONVERSATIONS
        path.contains("cache") -> ResourceCategory.CACHE
        path.contains("download") || path.contains("staging") || path.endsWith(".part") -> ResourceCategory.DOWNLOADS
        path.contains("log") -> ResourceCategory.LOGS
        path.contains("export") || path.contains("backup") -> ResourceCategory.BACKUP
        else -> ResourceCategory.OTHER
    }
}

class ResourceBudgetManager(private val context: Context) {
    companion object {
        const val MIN_STORAGE_GB = 1
        const val MAX_STORAGE_GB = 128
        private const val PREFS = "airi_resource_budget"
        private const val KEY_STORAGE_GB = "storage_budget_gb"
        private const val KEY_RAM_MB = "ram_budget_mb"
        private const val KEY_CPU_PERCENT = "cpu_budget_percent"
        private const val KEY_LAST_NOTIFICATION = "last_notification_at"
        private const val CHANNEL_ID = "airi_resource_alerts"
        private const val NOTIFICATION_ID = 4107
    }
    data class Snapshot(
        val storageBudgetBytes: Long, val storageUsedBytes: Long, val storageFreeBytes: Long, val storagePercent: Int,
        val deviceTotalBytes: Long, val deviceFreeBytes: Long, val deviceAllocatableBytes: Long,
        val totalRamBytes: Long, val availableRamBytes: Long, val ramBudgetBytes: Long, val processPssBytes: Long,
        val cpuCores: Int, val cpuBudgetPercent: Int, val storageWarning: Boolean, val deviceStorageWarning: Boolean, val ramWarning: Boolean,
        val categories: List<ResourceCategoryTotal>
    )
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lastNotificationAt = AtomicLong(prefs.getLong(KEY_LAST_NOTIFICATION, 0L))
    private val registry = ResourceRegistry(context)
    fun storageBudgetGb(): Int = prefs.getInt(KEY_STORAGE_GB, recommendedStorageBudgetGb()).coerceIn(MIN_STORAGE_GB, minOf(MAX_STORAGE_GB, recommendedStorageBudgetGb()))
    fun ramBudgetMb(): Int = prefs.getInt(KEY_RAM_MB, recommendedRamBudgetMb()).coerceAtLeast(512)
    fun cpuBudgetPercent(): Int = prefs.getInt(KEY_CPU_PERCENT, 100).coerceIn(25, 100)
    fun setStorageBudgetGb(value: Int) { prefs.edit().putInt(KEY_STORAGE_GB, value.coerceIn(MIN_STORAGE_GB, MAX_STORAGE_GB)).apply() }
    fun setRamBudgetMb(value: Int) { prefs.edit().putInt(KEY_RAM_MB, value.coerceAtLeast(512)).apply() }
    fun setCpuBudgetPercent(value: Int) { prefs.edit().putInt(KEY_CPU_PERCENT, value.coerceIn(25, 100)).apply() }
    fun recommendedStorageBudgetGb(): Int { val fs = StatFs(context.filesDir.absolutePath); val safe = (fs.availableBytes * 0.25 / (1024.0*1024*1024)).toInt(); return safe.coerceIn(2, 32) }
    fun snapshot(): Snapshot {
        val fs = StatFs(context.filesDir.absolutePath)
        val used = registry.reconcile().sumOf { it.sizeBytes }
        val budget = storageBudgetGb().toLong() * 1024L * 1024L * 1024L
        val memory = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it) }
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() * 1024L
        val deviceTotal = fs.totalBytes; val deviceFree = fs.availableBytes
        return Snapshot(budget, used, (budget-used).coerceAtLeast(0), ((used*100.0)/budget.coerceAtLeast(1)).toInt(), deviceTotal, deviceFree, fs.availableBytes, memory.totalMem, memory.availMem, ramBudgetMb().toLong()*1024*1024, pss, Runtime.getRuntime().availableProcessors().coerceAtLeast(1), cpuBudgetPercent(), ResourceBudgetPolicy.storageWarning(((used*100.0)/budget.coerceAtLeast(1)).toInt()), ResourceBudgetPolicy.deviceStorageWarning(deviceFree, deviceTotal), ResourceBudgetPolicy.ramWarning(memory.availMem, ramBudgetMb().toLong()*1024*1024), registry.totals())
    }
    fun notifyIfNeeded(snapshot: Snapshot = snapshot()) {
        if (!snapshot.storageWarning && !snapshot.deviceStorageWarning && !snapshot.ramWarning) return
        val now=System.currentTimeMillis(); if (now-lastNotificationAt.get()<6*60*60*1000L) return
        if (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val nm=context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,"AIRI resource alerts",NotificationManager.IMPORTANCE_DEFAULT))
        val msg=when { snapshot.deviceStorageWarning -> "Your device is running low on free storage."; snapshot.ramWarning -> "AIRI is under memory pressure."; else -> "AIRI is running low on managed storage." }
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context,CHANNEL_ID).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("AIRI Resource Center").setContentText(msg).setStyle(NotificationCompat.BigTextStyle().bigText(msg)).setAutoCancel(true).build())
        lastNotificationAt.set(now); prefs.edit().putLong(KEY_LAST_NOTIFICATION,now).apply()
    }
    private fun recommendedRamBudgetMb(): Int { val m=ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it) }; return (m.totalMem/1024/1024/2).toInt().coerceIn(512,8192) }
}
fun Long.asResourceSize(): String { if(this<1024L*1024L) return "${this/1024L} KB"; val gb=this.toDouble()/(1024.0*1024.0*1024.0); return if(gb>=1) "%.2f GB".format(Locale.US,gb) else "%.0f MB".format(Locale.US,gb*1024) }
