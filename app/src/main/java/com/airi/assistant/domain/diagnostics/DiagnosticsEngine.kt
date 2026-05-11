package com.airi.assistant.domain.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DiagnosticsEngine — continuous background health monitoring for AIRI.
 *
 * Runs periodic health checks and exposes live [HealthSnapshot] via
 * StateFlow so the UI and agent layer can react to degraded conditions.
 *
 * ## Checks performed
 *  - RAM availability (total, available, pressure flag)
 *  - Internal storage free space
 *  - Network connectivity (wifi / cellular / none)
 *  - Model storage directory accessibility
 *  - Thermal pressure (via ActivityManager.isLowRamDevice)
 *  - JVM heap usage
 *
 * ## Usage
 * ```kotlin
 * val engine = DiagnosticsEngine(context)
 * engine.start()
 * // Observe health:
 * engine.health.collect { snapshot -> ... }
 * ```
 */
class DiagnosticsEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Data types ────────────────────────────────────────────────────────────

    enum class NetworkState { NONE, WIFI, CELLULAR, ETHERNET }

    enum class StoragePressure { OK, LOW, CRITICAL }

    enum class RamPressure { OK, MODERATE, HIGH, CRITICAL }

    data class HealthSnapshot(
        val timestampMs:        Long             = System.currentTimeMillis(),
        // RAM
        val totalRamMb:         Long             = 0L,
        val availableRamMb:     Long             = 0L,
        val ramPressure:        RamPressure      = RamPressure.OK,
        val isLowRamDevice:     Boolean          = false,
        // Storage
        val totalStorageGb:     Float            = 0f,
        val freeStorageGb:      Float            = 0f,
        val storagePressure:    StoragePressure  = StoragePressure.OK,
        val modelDirAccessible: Boolean          = true,
        // Network
        val networkState:       NetworkState     = NetworkState.NONE,
        val isOnline:           Boolean          = false,
        // JVM heap
        val heapUsedMb:         Long             = 0L,
        val heapMaxMb:          Long             = 0L,
        // Overall
        val isHealthy:          Boolean          = true,
        val warnings:           List<String>     = emptyList()
    )

    private val _health = MutableStateFlow(HealthSnapshot())
    val health: StateFlow<HealthSnapshot> = _health.asStateFlow()

    private var isRunning = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (isRunning) return
        isRunning = true
        scope.launch {
            Log.i(TAG, "DiagnosticsEngine started intervalMs=$intervalMs")
            while (isRunning) {
                try {
                    val snapshot = runChecks()
                    _health.value = snapshot
                    if (snapshot.warnings.isNotEmpty()) {
                        LoggingService.warn(TAG, "Health warnings: ${snapshot.warnings.joinToString(" | ")}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Health check error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    fun runOnce(): HealthSnapshot = runChecks()

    // ── Check implementations ─────────────────────────────────────────────────

    private fun runChecks(): HealthSnapshot {
        val warnings = mutableListOf<String>()

        // ── RAM ───────────────────────────────────────────────────────────────
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / MB
        val availRamMb = memInfo.availMem / MB
        val usedRamPct = if (totalRamMb > 0) (totalRamMb - availRamMb) * 100 / totalRamMb else 0
        val isLowRam   = am?.isLowRamDevice ?: false
        val ramPressure = when {
            isLowRam || usedRamPct >= 90 -> RamPressure.CRITICAL
            usedRamPct >= 80             -> RamPressure.HIGH
            usedRamPct >= 65             -> RamPressure.MODERATE
            else                         -> RamPressure.OK
        }
        if (ramPressure == RamPressure.HIGH || ramPressure == RamPressure.CRITICAL) {
            warnings += "RAM pressure: ${usedRamPct}% used (${availRamMb}MB free)"
        }

        // ── Storage ───────────────────────────────────────────────────────────
        val stat = runCatching {
            StatFs(Environment.getDataDirectory().path)
        }.getOrNull()
        val totalStorageGb = stat?.let { it.totalBytes.toFloat() / GB } ?: 0f
        val freeStorageGb  = stat?.let { it.freeBytes.toFloat()  / GB } ?: 0f
        val freeStoragePct = if (totalStorageGb > 0) (freeStorageGb / totalStorageGb * 100).toInt() else 100
        val storagePressure = when {
            freeStorageGb < 0.5f || freeStoragePct < 5  -> StoragePressure.CRITICAL
            freeStorageGb < 2.0f || freeStoragePct < 15 -> StoragePressure.LOW
            else                                          -> StoragePressure.OK
        }
        if (storagePressure != StoragePressure.OK) {
            warnings += "Storage pressure: ${String.format("%.1f", freeStorageGb)}GB free"
        }

        // Model directory check
        val modelDir = context.getExternalFilesDir("models")
            ?: context.filesDir.resolve("models")
        val modelDirAccessible = modelDir.exists() || modelDir.mkdirs()

        // ── Network ───────────────────────────────────────────────────────────
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val nc      = cm?.getNetworkCapabilities(network)
        val networkState = when {
            nc == null                                           -> NetworkState.NONE
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WIFI
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CELLULAR
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.ETHERNET
            else                                                 -> NetworkState.NONE
        }
        val isOnline = networkState != NetworkState.NONE

        // ── JVM heap ──────────────────────────────────────────────────────────
        val runtime   = Runtime.getRuntime()
        val heapUsed  = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapMax   = runtime.maxMemory() / MB
        val heapPct   = if (heapMax > 0) heapUsed * 100 / heapMax else 0
        if (heapPct > 85) {
            warnings += "JVM heap pressure: ${heapPct}% used"
        }

        val isHealthy = warnings.isEmpty()
        return HealthSnapshot(
            timestampMs        = System.currentTimeMillis(),
            totalRamMb         = totalRamMb,
            availableRamMb     = availRamMb,
            ramPressure        = ramPressure,
            isLowRamDevice     = isLowRam,
            totalStorageGb     = totalStorageGb,
            freeStorageGb      = freeStorageGb,
            storagePressure    = storagePressure,
            modelDirAccessible = modelDirAccessible,
            networkState       = networkState,
            isOnline           = isOnline,
            heapUsedMb         = heapUsed,
            heapMaxMb          = heapMax,
            isHealthy          = isHealthy,
            warnings           = warnings
        )
    }

    companion object {
        private const val TAG                = "AIRI_DiagnosticsEngine"
        private const val DEFAULT_INTERVAL_MS = 30_000L  // 30s
        private const val MB                 = 1_048_576L
        private const val GB                 = 1_073_741_824L
    }
}
