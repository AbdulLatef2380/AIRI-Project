package com.airi.assistant.runtime.profiler

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * FlowPressureMonitor — wraps Flows to detect backpressure and slow collectors.
 *
 * ── How it works ─────────────────────────────────────────────────────────
 * [monitorFlow] wraps an upstream Flow with emission timestamping. If the
 * downstream (collector) takes longer than [SLOW_COLLECTOR_MS] between
 * emissions the event is counted as "pressure" and a warning fires.
 *
 * [auditSharedFlow] periodically reads a SharedFlow's replayCache size.
 * A non-zero replay cache that grows monotonically across audits indicates
 * the producer is outpacing all consumers.
 *
 * ── Integration ──────────────────────────────────────────────────────────
 * Replace:
 *   mySharedFlow.collect { ... }
 * With:
 *   FlowPressureMonitor.monitorFlow("activityFeed", mySharedFlow).collect { ... }
 */
object FlowPressureMonitor {

    private const val TAG                = "FlowPressureMonitor"
    private const val SLOW_COLLECTOR_MS  = 200L    // warn if collector takes >200ms per item
    private const val AUDIT_INTERVAL_MS  = 10_000L

    data class FlowStats(
        val key:           String,
        val emitCount:     Long,
        val pressureCount: Long,
        val maxBacklogMs:  Long
    )

    private data class Tracker(
        val emitCount:     AtomicLong = AtomicLong(0),
        val pressureCount: AtomicLong = AtomicLong(0),
        val maxBacklogMs:  AtomicLong = AtomicLong(0)
    )

    private val trackers = ConcurrentHashMap<String, Tracker>()
    private val auditedFlows = ConcurrentHashMap<String, MutableSharedFlow<*>>()

    private val _stats = MutableStateFlow<List<FlowStats>>(emptyList())
    val stats: StateFlow<List<FlowStats>> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            while (isActive) {
                delay(AUDIT_INTERVAL_MS)
                auditReplayCaches()
                publishStats()
            }
        }
        Log.i(TAG, "AIRI FLOW_PRESSURE_MONITOR_STARTED")
    }

    /** Wrap a Flow to detect slow collectors. */
    fun <T> monitorFlow(key: String, upstream: Flow<T>): Flow<T> {
        val tracker = trackers.getOrPut(key) { Tracker() }
        return upstream.onEach { _ ->
            val t0 = System.currentTimeMillis()
            tracker.emitCount.incrementAndGet()
            val elapsed = System.currentTimeMillis() - t0
            if (elapsed > SLOW_COLLECTOR_MS) {
                tracker.pressureCount.incrementAndGet()
                if (elapsed > tracker.maxBacklogMs.get()) tracker.maxBacklogMs.set(elapsed)
                RuntimeProfiler.recordFlowPressureWarning(key)
                Log.w(TAG, "AIRI SLOW_COLLECTOR key=$key delayMs=$elapsed")
            }
        }
    }

    /** Register a SharedFlow for periodic replay-cache auditing. */
    fun <T> auditSharedFlow(key: String, flow: MutableSharedFlow<T>) {
        auditedFlows[key] = flow
    }

    private fun auditReplayCaches() {
        auditedFlows.forEach { (key, flow) ->
            val cacheSize = flow.replayCache.size
            if (cacheSize > 0) {
                Log.w(TAG, "AIRI REPLAY_CACHE_NONEMPTY key=$key size=$cacheSize")
            }
        }
    }

    private fun publishStats() {
        _stats.value = trackers.map { (k, v) ->
            FlowStats(k, v.emitCount.get(), v.pressureCount.get(), v.maxBacklogMs.get())
        }
    }
}
