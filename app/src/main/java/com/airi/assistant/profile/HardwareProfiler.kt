package com.airi.assistant.profile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * HardwareProfiler — derives a [HardwareProfile] describing device capability
 * for use by the runtime router and model selector.
 *
 * ── WHY ───────────────────────────────────────────────────────────────────
 *
 *   llama.cpp model selection depends on available RAM, CPU core count, and
 *   whether the device has a capable GPU. HardwareProfiler centralises that
 *   logic so the RuntimeRouter, ModelConfigManager, and SubAgentRegistry can
 *   all query the same truth.
 *
 * ── REFRESH POLICY ────────────────────────────────────────────────────────
 *
 *   The profile is computed once per app launch (RAM can change at runtime
 *   due to other apps, but re-profiling mid-session causes flapping). The
 *   result is cached in-memory.
 */
class HardwareProfiler(private val context: Context) {

    private val TAG = "HardwareProfiler"

    @Volatile private var cachedProfile: HardwareProfile? = null

    data class HardwareProfile(
        val totalRamMb:         Long,
        val availableRamMb:     Long,
        val cpuCoreCount:       Int,
        val cpuAbi:             String,
        val sdkInt:             Int,
        val manufacturer:       String,
        val model:              String,
        val hasGpu:             Boolean,
        val maxRecommendedCtx:  Int,
        val tier:               Tier,
        /** AP-36: Battery level 0–100, or -1 if unknown. */
        val batteryPct:         Int = -1,
        /** AP-36: True when the device is in battery saver / power-save mode. */
        val isPowerSaveMode:    Boolean = false
    ) {
        enum class Tier { LOW, MID, HIGH, FLAGSHIP }

        fun supportsLocalLlm(): Boolean = totalRamMb >= 3_000
        fun supportsLargeModel(): Boolean = totalRamMb >= 6_000
    }

    suspend fun profile(): HardwareProfile {
        cachedProfile?.let { return it }
        return withContext(Dispatchers.Default) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)

            val totalRamMb     = mi.totalMem / (1024 * 1024)
            val availableRamMb = mi.availMem / (1024 * 1024)
            val cpuCores       = Runtime.getRuntime().availableProcessors()
            val abi            = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val hasGpu         = detectGpu()
            val maxCtx         = recommendedCtx(totalRamMb)
            val tier           = computeTier(totalRamMb, cpuCores)

            // AP-36: Read battery level and power-save mode at profile time.
            val batteryPct   = readBatteryPercent()
            val isPowerSave  = readPowerSaveMode()

            HardwareProfile(
                totalRamMb        = totalRamMb,
                availableRamMb    = availableRamMb,
                cpuCoreCount      = cpuCores,
                cpuAbi            = abi,
                sdkInt            = Build.VERSION.SDK_INT,
                manufacturer      = Build.MANUFACTURER,
                model             = Build.MODEL,
                hasGpu            = hasGpu,
                maxRecommendedCtx = maxCtx,
                tier              = tier,
                batteryPct        = batteryPct,
                isPowerSaveMode   = isPowerSave
            ).also {
                cachedProfile = it
                LoggingService.info(TAG,
                    "AIRI HARDWARE_PROFILE tier=${it.tier} ramMb=${it.totalRamMb} " +
                    "cores=${it.cpuCoreCount} battery=${it.batteryPct}% powerSave=${it.isPowerSaveMode}")
            }
        }
    }

    fun cachedOrNull(): HardwareProfile? = cachedProfile

    /** AP-36: Clears the cached profile so the next [profile] call reads fresh values. */
    fun invalidateCache() { cachedProfile = null }

    private fun detectGpu(): Boolean {
        return runCatching {
            val gpuDir = File("/sys/class/kgsl")
            gpuDir.exists() && gpuDir.isDirectory
        }.getOrDefault(false)
    }

    private fun recommendedCtx(totalRamMb: Long): Int = when {
        totalRamMb >= 12_000 -> 8192
        totalRamMb >= 6_000  -> 4096
        totalRamMb >= 3_000  -> 2048
        else                 -> 1024
    }

    private fun computeTier(ramMb: Long, cores: Int): HardwareProfile.Tier = when {
        ramMb >= 12_000 && cores >= 8  -> HardwareProfile.Tier.FLAGSHIP
        ramMb >= 6_000  && cores >= 6  -> HardwareProfile.Tier.HIGH
        ramMb >= 3_000  && cores >= 4  -> HardwareProfile.Tier.MID
        else                           -> HardwareProfile.Tier.LOW
    }

    /** AP-36: Returns battery level 0–100, or -1 if the sticky broadcast is unavailable. */
    private fun readBatteryPercent(): Int = runCatching {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return@runCatching -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
    }.getOrDefault(-1)

    /** AP-36: Returns true when the device is in power-save mode (API 21+). */
    private fun readPowerSaveMode(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode ?: false
    }.getOrDefault(false)
}
