package com.airi.assistant.runtime.memory

import android.content.Context
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
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * LeakInspectionRuntime — Phase R4 memory leak surface detector.
 *
 * Tracks object lifecycles via WeakReferences and alerts when objects
 * expected to be collected are still reachable after GC.
 *
 * ── Tracked categories ───────────────────────────────────────────────────
 *   ACTIVITY         — leaked Activities (via WeakRef + GC check)
 *   AUDIO_SESSION    — AudioRecord / AudioTrack not closed
 *   JNI_CONTEXT      — native llama_context not freed
 *   TERMINAL_SESSION — TerminalRuntime sessions not closed
 *   VIEWMODEL        — ViewModels alive after their owner is destroyed
 *   BITMAP           — large Bitmaps not recycled
 *
 * ── Usage ────────────────────────────────────────────────────────────────
 *   LeakInspectionRuntime.trackObject("AudioRecord", myAudioRecord, "TAG")
 *   // ... later when it should be released:
 *   LeakInspectionRuntime.expectReleased("AudioRecord")
 *
 * Note: This is a heuristic inspector, not a full heap profiler. For
 * production leak analysis use LeakCanary in debug builds alongside this.
 */
object LeakInspectionRuntime {

    private const val TAG               = "LeakInspectionRuntime"
    private const val AUDIT_INTERVAL_MS = 30_000L
    private const val GC_SETTLE_MS      = 500L

    enum class LeakCategory {
        ACTIVITY, AUDIO_SESSION, JNI_CONTEXT, TERMINAL_SESSION, VIEWMODEL, BITMAP, OTHER
    }

    data class LeakEntry(
        val key:          String,
        val category:     LeakCategory,
        val tag:          String,
        val trackedAtMs:  Long,
        val expectedDeadMs: Long,   // when we expect it to be GC'd
        val leaked:       Boolean   // WeakRef still resolves after GC + deadline
    )

    data class LeakReport(
        val leaks:            List<LeakEntry>,
        val suspectCount:     Int,
        val confirmedLeaks:   Int,
        val heapUsedMb:       Long,
        val generatedAtMs:    Long = System.currentTimeMillis()
    )

    private data class TrackedRef(
        val weak:          WeakReference<Any>,
        val category:      LeakCategory,
        val tag:           String,
        val trackedAtMs:   Long,
        val expectedDeadMs:Long
    )

    private val tracked    = ConcurrentHashMap<String, TrackedRef>()
    private val nativeRefs = AtomicInteger(0)   // JNI refs (manual count — no WeakRef for native)
    private val audioRefs  = AtomicInteger(0)   // open audio sessions

    private val _report = MutableStateFlow(LeakReport(emptyList(), 0, 0, 0))
    val report: StateFlow<LeakReport> = _report.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            Log.i(TAG, "AIRI LEAK_INSPECTOR_STARTED")
            while (isActive) {
                delay(AUDIT_INTERVAL_MS)
                audit()
            }
        }
    }

    // ── Tracking API ───────────────────────────────────────────────────────

    fun trackObject(
        key:            String,
        obj:            Any,
        tag:            String          = "",
        category:       LeakCategory    = LeakCategory.OTHER,
        expectedDeadMs: Long            = System.currentTimeMillis() + 60_000L
    ) {
        tracked[key] = TrackedRef(
            weak           = WeakReference(obj),
            category       = category,
            tag            = tag,
            trackedAtMs    = System.currentTimeMillis(),
            expectedDeadMs = expectedDeadMs
        )
    }

    fun expectReleased(key: String) {
        tracked.remove(key)
    }

    fun recordNativeContextOpen()  { nativeRefs.incrementAndGet() }
    fun recordNativeContextClose() { nativeRefs.decrementAndGet() }
    fun recordAudioOpen()          { audioRefs.incrementAndGet() }
    fun recordAudioClose()         { audioRefs.decrementAndGet() }

    // ── Internal audit ─────────────────────────────────────────────────────

    private fun audit() {
        // Hint GC before checking weak refs
        System.gc()
        Thread.sleep(GC_SETTLE_MS)

        val now    = System.currentTimeMillis()
        val leaks  = mutableListOf<LeakEntry>()
        val dead   = mutableListOf<String>()

        tracked.forEach { (key, ref) ->
            val alive = ref.weak.get() != null
            if (!alive) {
                dead.add(key)    // GC'd as expected — clean up
            } else if (now > ref.expectedDeadMs) {
                // Still reachable past its expected lifetime
                leaks.add(LeakEntry(
                    key           = key,
                    category      = ref.category,
                    tag           = ref.tag,
                    trackedAtMs   = ref.trackedAtMs,
                    expectedDeadMs= ref.expectedDeadMs,
                    leaked        = true
                ))
                Log.e(TAG, "AIRI LEAK_DETECTED key=$key category=${ref.category} tag=${ref.tag}")
            }
        }
        dead.forEach { tracked.remove(it) }

        if (nativeRefs.get() < 0) {
            Log.e(TAG, "AIRI JNI_OVERRELEASE nativeRefs=${nativeRefs.get()}")
        } else if (nativeRefs.get() > 1) {
            Log.w(TAG, "AIRI JNI_MULTI_CONTEXT nativeRefs=${nativeRefs.get()}")
        }

        if (audioRefs.get() > 2) {
            Log.w(TAG, "AIRI AUDIO_REF_ACCUMULATION count=${audioRefs.get()}")
        }

        val heapMb = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }) / 1_048_576

        _report.value = LeakReport(
            leaks          = leaks,
            suspectCount   = leaks.size,
            confirmedLeaks = leaks.count { it.leaked },
            heapUsedMb     = heapMb
        )

        Log.i(TAG, "AIRI LEAK_AUDIT confirmed=${leaks.size} " +
                "nativeRefs=${nativeRefs.get()} audioRefs=${audioRefs.get()} heap=${heapMb}MB")
    }
}
