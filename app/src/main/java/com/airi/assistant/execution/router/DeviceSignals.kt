package com.airi.assistant.execution.router

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.app.ActivityManager
import com.airi.assistant.core.debug.ThermalLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Snapshot of device runtime signals used by [RuntimeRouter] to make
 * routing decisions.
 *
 * All fields are read-only after construction. The router reads a fresh
 * snapshot before each routing call via [DeviceSignals.read] — this is a
 * suspend function that dispatches to [Dispatchers.Default] so it never
 * blocks the Main thread.
 *
 * Design principle: no caching here. The snapshot is intentionally cheap
 * to build (all reads are synchronous OS API calls that complete in < 1 ms)
 * so freshness is always preferred over staleness from a cache.
 */
data class DeviceSignals(
    val thermalLevel:     ThermalLevel,
    val thermalRaw:       Int,
    val availRamMb:       Long,
    val totalRamMb:       Long,
    val isLowMemory:      Boolean,
    val networkAvailable: Boolean,
    val networkType:      NetworkType,
    val batteryLevel:     Int,          // 0..100
    val isCharging:       Boolean,
    val cpuCores:         Int
) {

    enum class NetworkType { WIFI, CELLULAR, NONE }

    /** True when the device is likely capable of running local inference well. */
    val isLocalCapable: Boolean
        get() = !isLowMemory && availRamMb >= 800L &&
                thermalLevel != ThermalLevel.CRITICAL

    /** True when the device should prefer cloud to protect thermals or RAM. */
    val preferCloudForPerformance: Boolean
        get() = thermalLevel == ThermalLevel.SEVERE ||
                thermalLevel == ThermalLevel.CRITICAL ||
                isLowMemory ||
                availRamMb < 600L

    /** True when the device has sufficient charge for long local inference. */
    val hasSufficientPower: Boolean
        get() = isCharging || batteryLevel >= 20

    companion object {

        /**
         * Read a fresh snapshot of all device signals.
         * Dispatches to [Dispatchers.Default]; safe to call from any coroutine.
         * Wrapped in try/catch at each OS API boundary — never throws.
         */
        suspend fun read(context: Context): DeviceSignals =
            withContext(Dispatchers.Default) {
                val (thermal, thermalRaw) = readThermal(context)
                val (availRam, totalRam, lowMem) = readMemory(context)
                val (netAvail, netType) = readNetwork(context)
                val (battery, charging) = readBattery(context)
                DeviceSignals(
                    thermalLevel     = thermal,
                    thermalRaw       = thermalRaw,
                    availRamMb       = availRam,
                    totalRamMb       = totalRam,
                    isLowMemory      = lowMem,
                    networkAvailable = netAvail,
                    networkType      = netType,
                    batteryLevel     = battery,
                    isCharging       = charging,
                    cpuCores         = Runtime.getRuntime().availableProcessors()
                )
            }

        private fun readThermal(context: Context): Pair<ThermalLevel, Int> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return ThermalLevel.NONE to 0
            }
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val raw = pm.currentThermalStatus
                val level = when {
                    raw >= 4 -> ThermalLevel.CRITICAL
                    raw == 3 -> ThermalLevel.SEVERE
                    raw == 2 -> ThermalLevel.MODERATE
                    raw == 1 -> ThermalLevel.LIGHT
                    else     -> ThermalLevel.NONE
                }
                level to raw
            } catch (_: Throwable) {
                ThermalLevel.NONE to 0
            }
        }

        private fun readMemory(context: Context): Triple<Long, Long, Boolean> {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                Triple(
                    info.availMem / (1024L * 1024L),
                    info.totalMem  / (1024L * 1024L),
                    info.lowMemory
                )
            } catch (_: Throwable) {
                Triple(0L, 0L, false)
            }
        }

        private fun readNetwork(context: Context): Pair<Boolean, NetworkType> {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
                val net = cm.activeNetwork ?: return false to NetworkType.NONE
                val caps = cm.getNetworkCapabilities(net)
                    ?: return false to NetworkType.NONE
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                  caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                    else -> NetworkType.WIFI  // ethernet / VPN / etc.
                }
                hasInternet to type
            } catch (_: Throwable) {
                false to NetworkType.NONE
            }
        }

        private fun readBattery(context: Context): Pair<Int, Boolean> {
            return try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val intent = context.registerReceiver(null, filter) ?: return 100 to false
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct   = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                               status == BatteryManager.BATTERY_STATUS_FULL
                pct to charging
            } catch (_: Throwable) {
                100 to false
            }
        }
    }
}
