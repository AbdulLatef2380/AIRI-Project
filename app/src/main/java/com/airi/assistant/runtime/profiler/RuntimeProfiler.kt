package com.airi.assistant.runtime.profiler

import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong

/**
 * RuntimeProfiler — Phase R1 core profiler.
 *
 * Collects timing samples from all major AIRI subsystems:
 *   - LLM inference (llama.cpp token latency)
 *   - Orchestration throughput (agent task dispatch → result)
 *   - Flow emission cadence (backpressure detection)
 *   - JNI bridge round-trip
 *   - Voice pipeline (STT frame → transcript)
 *   - ActivityFeed throughput
 *
 * Usage — wrap any subsystem call:
 *   val result = RuntimeProfiler.profile("llama_decode") { nativeDecode(...) }
 *
 * Reports are emitted every [REPORT_INTERVAL_MS] to [report] StateFlow and
 * logged with AIRI_PROOF tags for adb logcat filtering.
 */
object RuntimeProfiler {

    private const val TAG = "RuntimeProfiler"
    private const val REPORT_INTERVAL_MS = 30_000L
    private const val SLOW_THRESHOLD_MS  = 500L       // warn if single sample > this
    private const val JNI_WARN_MS        = 50L        // JNI calls should be sub-50ms

    data class SampleBucket(
        val key: String,
        val count: Long,
        val totalMs: Long,
        val minMs: Long,
        val maxMs: Long,
        val p95Ms: Long           // approximated via reservoir
    ) {
        val avgMs: Long get() = if (count == 0L) 0L else totalMs / count
    }

    data class ProfileReport(
        val buckets: List<SampleBucket>,
        val droppedEventCount: Long,
        val flowPressureWarnings: Int,
        val slowCallCount: Long,
        val generatedAtMs: Long = System.currentTimeMillis()
    )

    // ── Internal state ─────────────────────────────────────────────────────
    private data class Accumulator(
        val count:   AtomicLong = AtomicLong(0),
        val total:   AtomicLong = AtomicLong(0),
        val min:     AtomicLong = AtomicLong(Long.MAX_VALUE),
        val max:     AtomicLong = AtomicLong(0),
        val slowCt:  AtomicLong = AtomicLong(0),
        // small circular reservoir for p95
        val reservoir: LongArray = LongArray(128)
    ) {
        @Volatile var resIdx = 0

        fun record(ms: Long) {
            count.incrementAndGet()
            total.addAndGet(ms)
            if (ms < min.get()) min.set(ms)
            if (ms > max.get()) max.set(ms)
            if (ms > SLOW_THRESHOLD_MS) slowCt.incrementAndGet()
            synchronized(reservoir) {
                reservoir[resIdx % reservoir.size] = ms
                resIdx++
            }
        }

        fun p95(): Long {
            val sample = synchronized(reservoir) { reservoir.copyOf() }
                .filter { it > 0 }.sorted()
            if (sample.isEmpty()) return 0L
            return sample[(sample.size * 0.95).toInt().coerceAtMost(sample.size - 1)]
        }

        fun toBucket(key: String) = SampleBucket(
            key     = key,
            count   = count.get(),
            totalMs = total.get(),
            minMs   = if (min.get() == Long.MAX_VALUE) 0 else min.get(),
            maxMs   = max.get(),
            p95Ms   = p95()
        )
    }

    private val accumulators      = ConcurrentHashMap<String, Accumulator>()
    private val droppedEvents     = AtomicLong(0)
    private val flowPressureWarns = AtomicLong(0)

    private val _report = MutableStateFlow(ProfileReport(emptyList(), 0, 0, 0))
    val report: StateFlow<ProfileReport> = _report.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Public API ──────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            Log.i(TAG, "AIRI_PROOF PROFILER_STARTED")
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                emitReport()
            }
        }
    }

    /** Inline profiling wrapper. Returns the block's result. */
    inline fun <T> profile(key: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            val ms = SystemClock.elapsedRealtime() - t0
            record(key, ms)
        }
    }

    fun record(key: String, durationMs: Long) {
        val acc = accumulators.getOrPut(key) { Accumulator() }
        acc.record(durationMs)
        if (durationMs > SLOW_THRESHOLD_MS) {
            Log.w(TAG, "AIRI_PROOF SLOW_CALL key=$key durationMs=$durationMs")
        }
        if (key.startsWith("jni_") && durationMs > JNI_WARN_MS) {
            Log.w(TAG, "AIRI_PROOF JNI_LATENCY_SPIKE key=$key durationMs=$durationMs")
        }
    }

    fun recordDroppedEvent() {
        droppedEvents.incrementAndGet()
        Log.w(TAG, "AIRI_PROOF EVENT_DROPPED total=${droppedEvents.get()}")
    }

    fun recordFlowPressureWarning(flowName: String) {
        flowPressureWarns.incrementAndGet()
        Log.w(TAG, "AIRI_PROOF FLOW_PRESSURE flow=$flowName total=${flowPressureWarns.get()}")
    }

    fun emitReport(): ProfileReport {
        val buckets    = accumulators.map { (k, v) -> v.toBucket(k) }.sortedBy { it.key }
        val slowTotal  = buckets.sumOf { it.count } // rough
        val rpt = ProfileReport(
            buckets               = buckets,
            droppedEventCount     = droppedEvents.get(),
            flowPressureWarnings  = flowPressureWarns.get().toInt(),
            slowCallCount         = accumulators.values.sumOf { it.slowCt.get() }
        )
        _report.value = rpt
        Log.i(TAG, "AIRI_PROOF PROFILE_REPORT buckets=${buckets.size} " +
                "dropped=${rpt.droppedEventCount} slowCalls=${rpt.slowCallCount}")
        buckets.forEach { b ->
            Log.i(TAG, "  ${b.key}: count=${b.count} avg=${b.avgMs}ms p95=${b.p95Ms}ms max=${b.maxMs}ms")
        }
        return rpt
    }

    fun reset() {
        accumulators.clear()
        droppedEvents.set(0)
        flowPressureWarns.set(0)
    }
}
