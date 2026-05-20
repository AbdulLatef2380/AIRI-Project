package com.airi.assistant.runtime.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * SessionIntegrityMonitor — Phase R3 long-session stability guard.
 *
 * Tracks runtime health across extended sessions (1h → overnight) by
 * watching for the canonical signs of degradation:
 *
 *   1. Progressive lag (heartbeat latency increasing over time)
 *   2. Memory accumulation (heap used growing without release)
 *   3. Orphan coroutine accumulation (registered but never completed)
 *   4. Event-bus saturation (emission rate exceeding drain rate)
 *   5. Stuck agents (tasks registered but not completing)
 *
 * ── Integration ──────────────────────────────────────────────────────────
 * • Call [registerCoroutine] + [unregisterCoroutine] around every long-lived
 *   coroutine launch. JobTracker extension below makes this one-liner.
 * • Call [recordAgentTaskStart] / [recordAgentTaskEnd] from AgentWorker.
 * • [start] runs the heartbeat loop on Dispatchers.Default.
 */
object SessionIntegrityMonitor {

    private const val TAG                  = "SessionIntegrityMonitor"
    private const val HEARTBEAT_INTERVAL_MS = 60_000L       // 1 min
    private const val LAG_WARN_MS          = 3_000L         // heartbeat delayed >3s = lag
    private const val MEMORY_GROWTH_WARN_MB = 50L           // 50MB growth per interval = warn
    private const val ORPHAN_WARN_COUNT    = 50             // >50 live coroutines = suspect
    private const val STUCK_AGENT_WARN_MS  = 5 * 60_000L   // agent stuck >5min = warn

    data class IntegritySnapshot(
        val sessionAgeMs:         Long,
        val heartbeatLatencyMs:   Long,
        val heapUsedMb:           Long,
        val heapDeltaMb:          Long,         // change since last snapshot
        val liveCoroutineCount:   Int,
        val orphanSuspects:       List<String>, // coroutine keys live > threshold
        val stuckAgentIds:        List<String>,
        val eventBusSaturation:   Boolean,
        val healthy:              Boolean
    )

    private val _snapshot = MutableStateFlow<IntegritySnapshot?>(null)
    val snapshot: StateFlow<IntegritySnapshot?> = _snapshot.asStateFlow()

    private val sessionStartMs      = AtomicLong(0)
    private val liveCoroutines      = ConcurrentHashMap<String, Long>() // key → start epoch
    private val activeAgentTasks    = ConcurrentHashMap<String, Long>() // taskId → start epoch
    private val eventBusEmitRate    = AtomicInteger(0)
    private val eventBusDrainRate   = AtomicInteger(0)
    private var lastHeapMb          = 0L
    private val scope               = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job?  = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        sessionStartMs.set(System.currentTimeMillis())
        lastHeapMb = heapUsedMb()
        heartbeatJob = scope.launch {
            Log.i(TAG, "AIRI_PROOF SESSION_MONITOR_STARTED")
            while (isActive) {
                val t0 = System.currentTimeMillis()
                delay(HEARTBEAT_INTERVAL_MS)
                val latency = System.currentTimeMillis() - t0 - HEARTBEAT_INTERVAL_MS
                checkIntegrity(latency.coerceAtLeast(0))
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        Log.i(TAG, "AIRI_PROOF SESSION_MONITOR_STOPPED age=${sessionAgeMs()}ms")
    }

    // ── Coroutine tracking ─────────────────────────────────────────────────

    fun registerCoroutine(key: String) {
        liveCoroutines[key] = System.currentTimeMillis()
    }

    fun unregisterCoroutine(key: String) {
        liveCoroutines.remove(key)
    }

    // ── Agent tracking ─────────────────────────────────────────────────────

    fun recordAgentTaskStart(taskId: String) {
        activeAgentTasks[taskId] = System.currentTimeMillis()
    }

    fun recordAgentTaskEnd(taskId: String) {
        activeAgentTasks.remove(taskId)
    }

    // ── Event-bus rate ─────────────────────────────────────────────────────

    fun recordEventEmit()  { eventBusEmitRate.incrementAndGet() }
    fun recordEventDrain() { eventBusDrainRate.incrementAndGet() }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun checkIntegrity(heartbeatLatencyMs: Long) {
        val now     = System.currentTimeMillis()
        val heapMb  = heapUsedMb()
        val delta   = heapMb - lastHeapMb
        lastHeapMb  = heapMb

        val orphanThresholdMs = HEARTBEAT_INTERVAL_MS * 10 // 10 intervals
        val orphans = liveCoroutines.filter { (_, startMs) ->
            now - startMs > orphanThresholdMs
        }.keys.toList()

        val stuckAgents = activeAgentTasks.filter { (_, startMs) ->
            now - startMs > STUCK_AGENT_WARN_MS
        }.keys.toList()

        val emitRate  = eventBusEmitRate.getAndSet(0)
        val drainRate = eventBusDrainRate.getAndSet(0)
        val saturated = emitRate > drainRate * 2 && emitRate > 100

        val healthy = heartbeatLatencyMs < LAG_WARN_MS &&
                delta < MEMORY_GROWTH_WARN_MB &&
                orphans.isEmpty() &&
                stuckAgents.isEmpty() &&
                !saturated

        val snap = IntegritySnapshot(
            sessionAgeMs        = sessionAgeMs(),
            heartbeatLatencyMs  = heartbeatLatencyMs,
            heapUsedMb          = heapMb,
            heapDeltaMb         = delta,
            liveCoroutineCount  = liveCoroutines.size,
            orphanSuspects      = orphans,
            stuckAgentIds       = stuckAgents,
            eventBusSaturation  = saturated,
            healthy             = healthy
        )
        _snapshot.value = snap

        if (!healthy) {
            Log.w(TAG, "AIRI_PROOF SESSION_DEGRADATION heartbeatLag=${heartbeatLatencyMs}ms " +
                    "heapDelta=${delta}MB orphans=${orphans.size} stuckAgents=${stuckAgents.size} " +
                    "saturated=$saturated")
        } else {
            Log.i(TAG, "AIRI_PROOF SESSION_HEALTHY age=${sessionAgeMs()}ms heap=${heapMb}MB")
        }

        if (liveCoroutines.size > ORPHAN_WARN_COUNT) {
            Log.w(TAG, "AIRI_PROOF HIGH_COROUTINE_COUNT count=${liveCoroutines.size}")
        }
    }

    private fun heapUsedMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    private fun sessionAgeMs() = System.currentTimeMillis() - sessionStartMs.get()
}
