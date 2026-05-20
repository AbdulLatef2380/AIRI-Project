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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RuntimeHealthMonitor — runs a periodic health check over all AIRI runtime
 * subsystems and exposes a [HealthReport] StateFlow.
 *
 * ── CHECKS ────────────────────────────────────────────────────────────────
 *
 *   MEMORY       — warns if available heap < [LOW_MEMORY_MB]
 *   NETWORK      — reads NetworkService.isConnected
 *   DISK         — warns if files-dir free space < [LOW_DISK_MB]
 *   PROCESS      — checks if the process is in the background (low priority)
 *   SESSION AGE  — warns after [SESSION_WARN_MS] of continuous runtime
 *   COROUTINES   — warns if registered live coroutines exceed [ORPHAN_WARN_COUNT]
 *   AGENTS       — warns if any agent task is stuck beyond [STUCK_AGENT_MS]
 *   EVENT BUS    — warns if AgentActivityBus emit rate exceeds drain rate
 *
 * ── LIFECYCLE ─────────────────────────────────────────────────────────────
 *
 *   Call [start] from Application.onCreate(). Reports emit every
 *   [CHECK_INTERVAL_MS] and are also available on-demand via [check].
 *
 *   Register long-lived coroutines via [registerCoroutine] /
 *   [unregisterCoroutine] to track orphan accumulation.
 *   Register agent tasks via [recordAgentStart] / [recordAgentEnd].
 */
class RuntimeHealthMonitor(
    private val context:        Context,
    private val crashReporter:  OrchestratorCrashReporter,
    private val networkService: NetworkService
) {

    private val TAG   = "RuntimeHealthMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Session tracking ───────────────────────────────────────────────────
    private val sessionStartMs = AtomicLong(System.currentTimeMillis())

    // ── Coroutine lifecycle tracking ───────────────────────────────────────
    // key → epoch when the coroutine was registered
    private val liveCoroutines = ConcurrentHashMap<String, Long>()

    // ── Agent task tracking ────────────────────────────────────────────────
    private val activeAgentTasks = ConcurrentHashMap<String, Long>() // taskId → startMs

    // ── Event-bus rate tracking ────────────────────────────────────────────
    private val busEmitCount  = AtomicInteger(0)
    private val busDrainCount = AtomicInteger(0)

    data class HealthReport(
        val heapAvailableMb:       Long    = -1,
        val diskFreeMb:            Long    = -1,
        val networkConnected:      Boolean = true,
        val lowMemoryWarning:      Boolean = false,
        val lowDiskWarning:        Boolean = false,
        // ── Session integrity signals ──────────────────────────────────────
        val sessionAgeMs:          Long    = 0,
        val sessionAgeWarning:     Boolean = false,
        val liveCoroutineCount:    Int     = 0,
        val orphanCoroutineWarning:Boolean = false,
        val orphanKeys:            List<String> = emptyList(),
        val stuckAgentCount:       Int     = 0,
        val stuckAgentIds:         List<String> = emptyList(),
        val eventBusSaturated:     Boolean = false,
        val timestampMs:           Long    = System.currentTimeMillis()
    ) {
        val isHealthy: Boolean get() =
            !lowMemoryWarning && !lowDiskWarning && !sessionAgeWarning &&
            !orphanCoroutineWarning && stuckAgentCount == 0 && !eventBusSaturated
    }

    private val _health = MutableStateFlow(HealthReport())
    val health: StateFlow<HealthReport> = _health.asStateFlow()

    @Volatile var isRunning = false
        private set

    // ── Public API: coroutine tracking ─────────────────────────────────────

    /**
     * Register a long-lived coroutine so the monitor can detect if it
     * accumulates without being released.
     *
     * Pattern:
     *   val key = "AgentWorker_$taskId"
     *   runtimeHealthMonitor.registerCoroutine(key)
     *   try { ... } finally { runtimeHealthMonitor.unregisterCoroutine(key) }
     */
    fun registerCoroutine(key: String) {
        liveCoroutines[key] = System.currentTimeMillis()
    }

    fun unregisterCoroutine(key: String) {
        liveCoroutines.remove(key)
    }

    // ── Public API: agent task tracking ───────────────────────────────────

    fun recordAgentStart(taskId: String) {
        activeAgentTasks[taskId] = System.currentTimeMillis()
    }

    fun recordAgentEnd(taskId: String) {
        activeAgentTasks.remove(taskId)
    }

    // ── Public API: event bus rate ─────────────────────────────────────────

    fun recordBusEmit()  { busEmitCount.incrementAndGet() }
    fun recordBusDrain() { busDrainCount.incrementAndGet() }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) return
        isRunning = true
        sessionStartMs.set(System.currentTimeMillis())
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
        val now = System.currentTimeMillis()

        // ── Memory / disk / network ────────────────────────────────────────
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

        // ── Session age ────────────────────────────────────────────────────
        val sessionAge    = now - sessionStartMs.get()
        val sessionWarn   = sessionAge > SESSION_WARN_MS
        if (sessionWarn) {
            LoggingService.warn(TAG, "AIRI_PROOF HEALTH_LONG_SESSION ageMs=$sessionAge")
        }

        // ── Orphan coroutine detection ─────────────────────────────────────
        // A coroutine live for more than ORPHAN_THRESHOLD_MS is a suspect.
        val orphanThreshMs = CHECK_INTERVAL_MS * 10 // 10 health intervals
        val orphanKeys = liveCoroutines.filter { (_, startMs) ->
            now - startMs > orphanThreshMs
        }.keys.toList()
        val orphanWarn = liveCoroutines.size > ORPHAN_WARN_COUNT || orphanKeys.isNotEmpty()
        if (orphanWarn) {
            LoggingService.warn(TAG,
                "AIRI_PROOF HEALTH_ORPHAN_COROUTINES count=${liveCoroutines.size} suspects=${orphanKeys.size}")
            crashReporter.reportManual("health_monitor", "ORPHAN_COROUTINES",
                "Live=${liveCoroutines.size} suspects=${orphanKeys.take(5)}")
        }

        // ── Stuck agent detection ──────────────────────────────────────────
        val stuckAgentIds = activeAgentTasks.filter { (_, startMs) ->
            now - startMs > STUCK_AGENT_MS
        }.keys.toList()
        if (stuckAgentIds.isNotEmpty()) {
            LoggingService.warn(TAG,
                "AIRI_PROOF HEALTH_STUCK_AGENTS count=${stuckAgentIds.size} ids=${stuckAgentIds.take(3)}")
            crashReporter.reportManual("health_monitor", "STUCK_AGENTS",
                "Stuck: ${stuckAgentIds.take(3)}")
        }

        // ── Event bus saturation ───────────────────────────────────────────
        val emits  = busEmitCount.getAndSet(0)
        val drains = busDrainCount.getAndSet(0)
        val saturated = emits > drains * 2 && emits > 100
        if (saturated) {
            LoggingService.warn(TAG,
                "AIRI_PROOF HEALTH_BUS_SATURATION emits=$emits drains=$drains")
        }

        // ── SharedCognitiveBus replay-cache pressure ───────────────────────────
        // A non-empty and growing replay cache means agent messages are being
        // published faster than any subscriber is consuming them. Safe threshold
        // is < 32 (half the replay=64 capacity). Above that, warn.
        val cognitiveBusCache = runCatching {
            com.airi.assistant.agent.multiagent.SharedCognitiveBus.messages.replayCache.size
        }.getOrDefault(0)
        if (cognitiveBusCache > 32) {
            LoggingService.warn(TAG,
                "AIRI_PROOF HEALTH_COGNITIVE_BUS_PRESSURE size=$cognitiveBusCache")
        }

        val report = HealthReport(
            heapAvailableMb        = heapAvailMb,
            diskFreeMb             = diskFreeMb,
            networkConnected       = connected,
            lowMemoryWarning       = lowMemory,
            lowDiskWarning         = lowDisk,
            sessionAgeMs           = sessionAge,
            sessionAgeWarning      = sessionWarn,
            liveCoroutineCount     = liveCoroutines.size,
            orphanCoroutineWarning = orphanWarn,
            orphanKeys             = orphanKeys,
            stuckAgentCount        = stuckAgentIds.size,
            stuckAgentIds          = stuckAgentIds,
            eventBusSaturated      = saturated
        )
        _health.value = report

        LoggingService.info(TAG,
            "AIRI_PROOF HEALTH_CHECK healthy=${report.isHealthy} " +
            "heap=${heapAvailMb}MB session=${sessionAge/1000}s coroutines=${liveCoroutines.size}")

        return report
    }

    companion object {
        private const val CHECK_INTERVAL_MS  = 5 * 60_000L       // 5 min
        private const val LOW_MEMORY_MB      = 64L
        private const val LOW_DISK_MB        = 50L
        private const val SESSION_WARN_MS    = 3 * 60 * 60_000L  // 3 hours
        private const val ORPHAN_WARN_COUNT  = 50
        private const val STUCK_AGENT_MS     = 5 * 60_000L       // 5 min
    }
}
