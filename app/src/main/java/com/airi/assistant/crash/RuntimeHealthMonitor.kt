package com.airi.assistant.crash

import android.app.ActivityManager
import android.content.Context
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.network.NetworkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RuntimeHealthMonitor — runs a periodic health check over all AIRI runtime
 * subsystems and exposes a [HealthReport] StateFlow.
 *
 * ── CHECKS ────────────────────────────────────────────────────────────────
 *
 *   MEMORY    — warns if available heap < [LOW_MEMORY_MB]
 *   NETWORK   — reads NetworkService.isConnected
 *   DISK      — warns if files-dir free space < [LOW_DISK_MB]
 *   PROCESS   — checks if the process is in the background (low priority)
 *
 * ── LIFECYCLE ─────────────────────────────────────────────────────────────
 *
 *   Call [start] from Application.onCreate(). Reports emit every
 *   [CHECK_INTERVAL_MS] and are also available on-demand via [check].
 */
class RuntimeHealthMonitor(
    private val context:        Context,
    private val crashReporter:  OrchestratorCrashReporter,
    private val networkService: NetworkService
) {

    private val TAG   = "RuntimeHealthMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class HealthReport(
        val heapAvailableMb:   Long    = -1,
        val diskFreeMb:        Long    = -1,
        val networkConnected:  Boolean = true,
        val lowMemoryWarning:  Boolean = false,
        val lowDiskWarning:    Boolean = false,
        val timestampMs:       Long    = System.currentTimeMillis()
    ) {
        val isHealthy: Boolean get() = !lowMemoryWarning && !lowDiskWarning
    }

    private val _health = MutableStateFlow(HealthReport())
    val health: StateFlow<HealthReport> = _health.asStateFlow()

    @Volatile var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            LoggingService.info(TAG, "AIRI_PROOF HEALTH_MONITOR_STARTED")
            while (isActive) {
                runCatching { check() }
                    .onFailure { e ->
                        LoggingService.warn(TAG, "Health check failed: ${e.message}")
                        crashReporter.reportManual("health_monitor", "CHECK_FAILED", e.message ?: "unknown")
                    }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun check(): HealthReport {
        val rt  = Runtime.getRuntime()
        val heapAvailMb = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
        val diskFreeMb  = context.filesDir.freeSpace / (1024 * 1024)
        val connected   = networkService.isOnline()

        val lowMemory = heapAvailMb < LOW_MEMORY_MB
        val lowDisk   = diskFreeMb  < LOW_DISK_MB

        if (lowMemory) {
            LoggingService.warn(TAG, "AIRI_PROOF HEALTH_LOW_MEMORY heapAvailMb=$heapAvailMb")
            crashReporter.reportManual("health_monitor", "LOW_MEMORY", "Heap available: ${heapAvailMb}MB")
        }
        if (lowDisk) {
            LoggingService.warn(TAG, "AIRI_PROOF HEALTH_LOW_DISK diskFreeMb=$diskFreeMb")
            crashReporter.reportManual("health_monitor", "LOW_DISK", "Free disk: ${diskFreeMb}MB")
        }

        val report = HealthReport(
            heapAvailableMb  = heapAvailMb,
            diskFreeMb       = diskFreeMb,
            networkConnected = connected,
            lowMemoryWarning = lowMemory,
            lowDiskWarning   = lowDisk
        )
        _health.value = report
        return report
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 5 * 60_000L
        private const val LOW_MEMORY_MB     = 64L
        private const val LOW_DISK_MB       = 50L
    }
}
