package com.airi.assistant.core.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which subsystem was last responsible for setting the active PerformanceMode.
 * Displayed in the Runtime Status Panel so the user can see whether the current
 * mode is their explicit choice or a supervisor-enforced downgrade.
 */
enum class ModeSource {
    USER,               // User tapped a radio button on PerformanceScreen
    SUPERVISOR_THERMAL, // RuntimeSupervisor downgraded due to thermal pressure
    SUPERVISOR_MEMORY,  // RuntimeSupervisor downgraded due to memory pressure
    MANUAL_OVERRIDE     // Programmatic hot-swap (e.g. model-swap re-application)
}

/**
 * Coarse thermal level label derived from PowerManager.THERMAL_STATUS_* (API 29+).
 * Displayed in the Runtime Status Panel so the user can see device heat state.
 * NONE is also the fallback for pre-API-29 devices and any read errors.
 */
enum class ThermalLevel {
    NONE,     // status 0 — nominal
    LIGHT,    // status 1 — light throttle warning
    MODERATE, // status 2 — supervisor will cap at BALANCED
    SEVERE,   // status 3 — supervisor forces FAST
    CRITICAL  // status ≥ 4 — maximum throttling
}

/**
 * State-machine phase of the current (or most recent) generation turn.
 * Updated at lifecycle boundaries only — never per-token — to avoid
 * unnecessary StateFlow emissions in the hot path.
 */
enum class GenerationPhase {
    IDLE,       // no generation in flight
    PREFILL,    // prompt being tokenized and primed into KV cache
    GENERATE,   // first token received; sampling loop active
    CANCELLED,  // generation cancelled (by user or supervisor)
    CLEANUP     // native context being reset after an error
}

/** Severity of a runtime timeline event. */
enum class EventSeverity { INFO, WARN, ERROR }

/**
 * A single entry in the runtime event timeline.
 * All fields are primitives / Strings — zero allocation on reads in Compose.
 */
data class RuntimeEvent(
    val timestampMs: Long,
    val subsystem:   String,
    val severity:    EventSeverity,
    val reason:      String
)

/**
 * Immutable snapshot of the full runtime diagnostics state.
 *
 * Owned by ChatViewModel; published as a [StateFlow]. Compose renders it
 * without modification — all derived values (warnings, formatted strings)
 * are pre-computed in the ViewModel before the snapshot is emitted so that
 * no heavy formatting work happens during recomposition.
 */
data class RuntimeDiagnosticsState(
    // ── Mode ─────────────────────────────────────────────────────────────────
    val effectiveMode:       String          = "—",
    val modeSource:          ModeSource      = ModeSource.USER,

    // ── Thermal ──────────────────────────────────────────────────────────────
    val thermalLevel:        ThermalLevel    = ThermalLevel.NONE,
    val thermalRaw:          Int             = 0,

    // ── Memory ───────────────────────────────────────────────────────────────
    val availRamMb:          Long            = 0L,
    val isLowMemory:         Boolean         = false,

    // ── Context usage ────────────────────────────────────────────────────────
    val kvUsed:              Int             = 0,
    val kvMax:               Int             = 0,

    // ── Model ────────────────────────────────────────────────────────────────
    val modelName:           String          = "—",
    val modelQuant:          String          = "—",

    // ── Generation ───────────────────────────────────────────────────────────
    val generationPhase:     GenerationPhase = GenerationPhase.IDLE,
    val tokensPerSec:        Float           = 0f,

    // ── Speculative / GPU ────────────────────────────────────────────────────
    val draftModelActive:    Boolean         = false,
    val gpuVulkanActive:     Boolean         = false,

    // ── Advanced ─────────────────────────────────────────────────────────────
    val sessionId:           Long            = 0L,
    val generationId:        Long            = 0L,
    val replayTokenCount:    Int             = 0,
    val nCtx:                Int             = 0,
    val nThreads:            Int             = 0,
    val runtimeUptimeMs:     Long            = 0L,
    val generationDurationMs: Long           = 0L,
    val speculativeActive:   Boolean         = false,

    // ── Execution layer (Hybrid Orchestrator) ────────────────────────────────
    val execActiveBackend:   String          = "none",   // "local_llama" | "cloud" | "none"
    val execActiveProvider:  String          = "",        // CloudProvider.name when cloud is active
    val execLastOrigin:      String          = "NONE",   // ExecOrigin.name of last completed turn
    val execPromptTokens:    Int             = 0,
    val execCompletionTokens: Int            = 0,
    val execRetryCount:      Int             = 0,
    val execFallbackCount:   Int             = 0,
    val execLastErrorMsg:    String          = "",
    val execIsStreaming:     Boolean         = false,
    val execCloudTokensToday: Long           = 0L,

    // ── Derived warnings (pre-computed in ViewModel) ─────────────────────────
    val warnings:            List<String>    = emptyList()
)

/**
 * Thread-safe, bounded ring buffer for the last [MAX_EVENTS] runtime events.
 *
 * Backed by a [MutableStateFlow<List<RuntimeEvent>>] so Compose can observe
 * it without polling. Uses a CAS loop to handle concurrent posts from multiple
 * coroutines without a JVM-level lock.
 *
 * Design constraints:
 *  - Max [MAX_EVENTS] entries — oldest entry dropped on overflow.
 *  - Events are posted from the ViewModel (never from composables).
 *  - The stored list is an immutable snapshot after every CAS; readers never
 *    see a partial update.
 *  - [clear] is available for model-swap cleanup paths.
 */
object RuntimeEventLog {

    const val MAX_EVENTS = 100

    private val _events = MutableStateFlow<List<RuntimeEvent>>(emptyList())
    val events: StateFlow<List<RuntimeEvent>> = _events.asStateFlow()

    /**
     * Append an event. Thread-safe via CAS. Never blocks.
     * Oldest entry is evicted when the buffer is full.
     */
    fun post(subsystem: String, severity: EventSeverity, reason: String) {
        val event = RuntimeEvent(
            timestampMs = System.currentTimeMillis(),
            subsystem   = subsystem,
            severity    = severity,
            reason      = reason
        )
        while (true) {
            val cur     = _events.value
            val updated = if (cur.size >= MAX_EVENTS) cur.drop(1) + event else cur + event
            if (_events.compareAndSet(cur, updated)) break
        }
    }

    /** Reset the buffer. Called on ViewModel.onCleared() to prevent leaks. */
    fun clear() { _events.value = emptyList() }
}
