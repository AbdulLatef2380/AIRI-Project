package com.airi.assistant.ai

import android.app.ActivityManager
import android.content.Context
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * InferenceHealthMonitor — comprehensive LLM runtime health state machine.
 *
 * Goes beyond [InferenceWatchdog] (which detects stuck generations) to provide
 * a holistic, continuously-updated view of the inference runtime:
 *
 * ── MONITORS ─────────────────────────────────────────────────────────────────
 *
 * 1. FREE RAM — available system memory vs. estimated model footprint.
 * 2. JVM HEAP — current heap usage and proximity to heap limit.
 * 3. NATIVE LIBRARY STATUS — whether libairi_native.so is loaded.
 * 4. MODEL LOAD STATE — whether a model is currently loaded.
 * 5. GENERATION HEALTH — token rate and generation count since start.
 * 6. KV CACHE HEALTH — indirectly via session reset counter.
 * 7. THERMAL PRESSURE — isLowRamDevice flag from ActivityManager.
 *
 * ── REPAIR ACTIONS ────────────────────────────────────────────────────────────
 *
 * When [InferenceHealth.status] enters DEGRADED or CRITICAL:
 *   - Emits actionable [RepairSuggestion]s to callers (do not act autonomously).
 *   - Logs an AIRI_PROOF_HEALTH event for audit.
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 *
 * Wire into ServiceLocator. [HybridInferenceOrchestrator] reads
 * [currentHealth] for memory-aware routing. [DiagnosticsEngine] subscribes
 * to [health] for the DebugPanel health grid.
 */
class InferenceHealthMonitor(private val context: Context) {

    private val TAG   = "InferenceHealthMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Counters ──────────────────────────────────────────────────────────────

    private val tokensSinceStart  = AtomicLong(0L)
    private val generationCount   = AtomicLong(0L)
    private val kvResetCount      = AtomicLong(0L)
    private val lastTokenTimestamp = AtomicLong(System.currentTimeMillis())
    private val isGenerating       = AtomicBoolean(false)

    // ── Health state ──────────────────────────────────────────────────────────

    private val _health = MutableStateFlow(InferenceHealth())
    val health: StateFlow<InferenceHealth> = _health.asStateFlow()

    fun currentHealth(): InferenceHealth = _health.value

    // ── Data classes ──────────────────────────────────────────────────────────

    enum class HealthStatus { NOMINAL, DEGRADED, CRITICAL }

    data class InferenceHealth(
        val status:           HealthStatus = HealthStatus.NOMINAL,
        val timestampMs:      Long         = System.currentTimeMillis(),
        // RAM
        val totalRamMb:       Long         = 0L,
        val freeRamMb:        Long         = 0L,
        val isLowRamDevice:   Boolean      = false,
        // JVM heap
        val heapUsedMb:       Long         = 0L,
        val heapMaxMb:        Long         = 0L,
        val heapPressurePct:  Int          = 0,
        // Model
        val isNativeLoaded:   Boolean      = false,
        val isModelLoaded:    Boolean      = false,
        val modelName:        String       = "",
        // Generation
        val totalTokens:      Long         = 0L,
        val totalGenerations: Long         = 0L,
        val kvResets:         Long         = 0L,
        val isCurrentlyGenerating: Boolean = false,
        // Diagnostics
        val repairSuggestions: List<RepairSuggestion> = emptyList(),
    )

    enum class RepairSuggestion {
        LOAD_SMALLER_MODEL,
        FREE_APP_MEMORY,
        RESTART_INFERENCE_SESSION,
        REDUCE_CONTEXT_WINDOW,
        ENABLE_CLOUD_FALLBACK,
        RELOAD_NATIVE_LIBRARY,
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private var monitorJob: Job? = null

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            Log.i(TAG, "InferenceHealthMonitor started (interval=${POLL_INTERVAL_MS}ms)")
            while (isActive) {
                runCatching { updateHealth() }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    // ── Telemetry hooks (called by LlamaManager/InferenceManager) ────────────

    fun onGenerationStart() {
        isGenerating.set(true)
        generationCount.incrementAndGet()
        lastTokenTimestamp.set(System.currentTimeMillis())
    }

    fun onToken() {
        tokensSinceStart.incrementAndGet()
        lastTokenTimestamp.set(System.currentTimeMillis())
    }

    fun onGenerationEnd() {
        isGenerating.set(false)
    }

    fun onKvReset() {
        kvResetCount.incrementAndGet()
    }

    // ── Health computation ────────────────────────────────────────────────────

    private fun updateHealth() {
        val am    = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi    = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)

        val totalRamMb  = mi.totalMem / MB
        val freeRamMb   = mi.availMem / MB
        val isLowRam    = am?.isLowRamDevice ?: false

        val runtime      = Runtime.getRuntime()
        val heapUsedMb   = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapMaxMb    = runtime.maxMemory() / MB
        val heapPct      = if (heapMaxMb > 0) ((heapUsedMb * 100) / heapMaxMb).toInt() else 0

        val nativeLoaded = LlamaNative.isAvailable()
        val modelLoaded  = ModelManager.getCurrent() != null
        val modelName    = ModelManager.getCurrent()?.name ?: ""

        val suggestions  = buildRepairSuggestions(
            freeRamMb    = freeRamMb,
            heapPct      = heapPct,
            nativeLoaded = nativeLoaded,
            modelLoaded  = modelLoaded,
        )

        val status = when {
            !nativeLoaded                  -> HealthStatus.CRITICAL
            freeRamMb < CRITICAL_RAM_MB    -> HealthStatus.CRITICAL
            heapPct > CRITICAL_HEAP_PCT    -> HealthStatus.CRITICAL
            freeRamMb < WARN_RAM_MB        -> HealthStatus.DEGRADED
            heapPct > WARN_HEAP_PCT        -> HealthStatus.DEGRADED
            isLowRam && !modelLoaded       -> HealthStatus.DEGRADED
            else                           -> HealthStatus.NOMINAL
        }

        val health = InferenceHealth(
            status               = status,
            timestampMs          = System.currentTimeMillis(),
            totalRamMb           = totalRamMb,
            freeRamMb            = freeRamMb,
            isLowRamDevice       = isLowRam,
            heapUsedMb           = heapUsedMb,
            heapMaxMb            = heapMaxMb,
            heapPressurePct      = heapPct,
            isNativeLoaded       = nativeLoaded,
            isModelLoaded        = modelLoaded,
            modelName            = modelName,
            totalTokens          = tokensSinceStart.get(),
            totalGenerations     = generationCount.get(),
            kvResets             = kvResetCount.get(),
            isCurrentlyGenerating = isGenerating.get(),
            repairSuggestions    = suggestions,
        )

        if (health.status != _health.value.status) {
            Log.w(TAG, "AIRI_PROOF_HEALTH status=${health.status.name} freeRam=${freeRamMb}MB heap=${heapPct}%")
        }

        _health.value = health
    }

    private fun buildRepairSuggestions(
        freeRamMb:    Long,
        heapPct:      Int,
        nativeLoaded: Boolean,
        modelLoaded:  Boolean,
    ): List<RepairSuggestion> = buildList {
        if (!nativeLoaded)              add(RepairSuggestion.RELOAD_NATIVE_LIBRARY)
        if (freeRamMb < CRITICAL_RAM_MB) {
            add(RepairSuggestion.LOAD_SMALLER_MODEL)
            add(RepairSuggestion.ENABLE_CLOUD_FALLBACK)
            add(RepairSuggestion.FREE_APP_MEMORY)
        } else if (freeRamMb < WARN_RAM_MB) {
            add(RepairSuggestion.REDUCE_CONTEXT_WINDOW)
            add(RepairSuggestion.LOAD_SMALLER_MODEL)
        }
        if (heapPct > CRITICAL_HEAP_PCT) add(RepairSuggestion.RESTART_INFERENCE_SESSION)
        if (kvResetCount.get() > KV_RESET_ALARM) add(RepairSuggestion.RESTART_INFERENCE_SESSION)
    }

    companion object {
        private const val MB                 = 1_048_576L
        private const val POLL_INTERVAL_MS   = 8_000L
        private const val WARN_RAM_MB        = 400L
        private const val CRITICAL_RAM_MB    = 150L
        private const val WARN_HEAP_PCT      = 70
        private const val CRITICAL_HEAP_PCT  = 90
        private const val KV_RESET_ALARM     = 10L
    }
}
