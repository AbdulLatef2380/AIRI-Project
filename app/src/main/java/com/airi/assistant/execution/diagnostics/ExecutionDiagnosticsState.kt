package com.airi.assistant.execution.diagnostics

import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.cloud.CloudErrorType

/**
 * Live snapshot of the Hybrid Execution layer's runtime state.
 *
 * Published as a [kotlinx.coroutines.flow.StateFlow] by [HybridOrchestrator]
 * and consumed by:
 *  - [ExecutionModePanel] — active backend badge, token budget bar
 *  - [PerformanceScreen]  — latency, retry/fallback counters
 *  - [RuntimeDiagnosticsState] — merged into the global diagnostics snapshot
 *
 * All fields are primitives or immutable types — zero allocation on reads
 * in Compose recomposition.
 */
data class ExecutionDiagnosticsState(

    // ── Active execution ──────────────────────────────────────────────────────
    val isStreaming:       Boolean    = false,
    val activeBackend:    String     = "none",   // "local_llama", "cloud", "none"
    val activeProvider:   CloudProvider? = null,  // Non-null when activeBackend == "cloud"
    val activeOrigin:     ExecOrigin  = ExecOrigin.NONE,

    // ── Latest turn metrics ───────────────────────────────────────────────────
    val lastPromptTokens:      Int    = 0,
    val lastCompletionTokens:  Int    = 0,
    val lastStreamDurationMs:  Long   = 0L,
    val lastProviderLatencyMs: Long   = 0L,       // Time to first token

    // ── Reliability counters (cumulative for the session) ─────────────────────
    val retryCount:        Int    = 0,
    val fallbackCount:     Int    = 0,
    val cancellationCount: Int    = 0,

    // ── Last failure context ──────────────────────────────────────────────────
    val lastErrorType:     CloudErrorType? = null,
    val lastErrorMessage:  String = "",
    val lastCancelReason:  String = "",
    val lastFallbackFrom:  String = "",   // backend that triggered fallback
    val lastFallbackTo:    String = "",   // backend fallback landed on
    val lastFallbackReason: String = "",

    // ── Session transition history (ring buffer, last 20 events) ──────────────
    val transitionHistory: List<ExecTransitionEvent> = emptyList()
)

/**
 * A single execution backend transition event.
 * Stored in [ExecutionDiagnosticsState.transitionHistory] (ring buffer, max 20).
 */
data class ExecTransitionEvent(
    val timestampMs: Long,
    val fromBackend: String,
    val toBackend:   String,
    val reason:      String,
    val origin:      ExecOrigin
) {
    val formattedTime: String get() {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(timestampMs))
    }
}
