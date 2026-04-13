package com.airi.assistant.ai

import android.app.ActivityManager
import android.content.Context

enum class DeviceTier { LOW, MID, HIGH }

data class DeviceProfile(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val cpuCores: Int,
    val tier: DeviceTier
)

object DeviceProfiler {
    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).toInt()
        val availableRamMb = (memInfo.availMem / (1024L * 1024L)).toInt()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val tier = when {
            totalRamMb <= 3072 -> DeviceTier.LOW   // ≤ 3 GB
            totalRamMb <= 6144 -> DeviceTier.MID   // 4–6 GB
            else               -> DeviceTier.HIGH  // ≥ 8 GB
        }
        return DeviceProfile(totalRamMb, availableRamMb, cpuCores, tier)
    }
}
