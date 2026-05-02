package com.airi.assistant.execution

import android.content.Context
import android.util.Log
import com.airi.assistant.core.debug.EventSeverity
import com.airi.assistant.core.debug.RuntimeEventLog
import com.airi.assistant.execution.backend.RuntimeBackend
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.execution.privacy.SanitizationResult
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.execution.router.RuntimeRouter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Top-level entry point for the Hybrid Execution layer.
 *
 * [executeStream] is the single method callers use to generate responses.
 * It handles:
 *  1. Privacy guard evaluation — sanitizes or blocks cloud-bound requests
 *  2. Routing decision via [RuntimeRouter]
 *  3. Primary backend execution with automatic fallback on failure
 *  4. Stale-stream protection — cancellation guard via [AtomicBoolean]
 *  5. ExecOrigin tagging — notifies the caller which backend produced the result
 *  6. Audit logging to [RuntimeEventLog]
 *
 * ## Cancellation contract
 * Callers cancel ongoing orchestration by calling [cancel]. The orchestrator
 * sets a cancel flag that is checked before each backend attempt. In-flight
 * backend calls are cancelled via coroutine structural cancellation (the caller
 * is responsible for cancelling the coroutine scope).
 *
 * ## Stale-stream protection
 * Each [executeStream] call increments a generation counter. If a new call
 * arrives while a previous one is still running (which should not happen in
 * normal single-turn chat), the older call's token callbacks are silently
 * dropped. This protects against race conditions during rapid request cancellation
 * and re-submission.
 *
 * ## Thread safety
 * [cancel] may be called from any thread. All other methods must be called
 * from a coroutine (any dispatcher).
 *
 * ## Agent foundation
 * [executeStream] accepts a [ExecutionRequest.requiresToolCalling] flag.
 * When true and the backend reports [CapabilityProfile.supportsToolCalling],
 * the response is passed to the tool call parser (future implementation).
 * This wires tool calling into the execution path without any changes to
 * the routing or backend layers.
 */
class HybridOrchestrator(
    private val router: RuntimeRouter,
    private val prefs:  ExecModePreferences
) {

    // Monotonically increasing generation counter for stale-stream detection.
    @Volatile private var currentGenId: Long = 0L
    private val cancelled = AtomicBoolean(false)

    /** Call to cancel any in-flight execution. Thread-safe. */
    fun cancel() {
        cancelled.set(true)
        Log.i(TAG, "HybridOrchestrator: cancel requested")
        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN, "Cancel requested")
    }

    /** Clear the cancel flag before starting a new generation. */
    fun resetCancel() {
        cancelled.set(false)
    }

    /**
     * Execute a request through the appropriate backend(s) with full
     * routing, privacy, fallback, and origin-tagging.
     *
     * @param request     Fully-formed execution request.
     * @param context     Android context for device signal reading.
     * @param onToken     Called for each streaming token. May be called from
     *                    any thread; UI updates must be dispatched to Main.
     * @param onComplete  Called exactly once when generation succeeds.
     *                    Receives (fullText, latencyMs, origin).
     * @param onError     Called exactly once when ALL backends have failed.
     *                    Receives (errorMessage, origin).
     */
    suspend fun executeStream(
        request:    ExecutionRequest,
        context:    Context,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long, ExecOrigin) -> Unit,
        onError:    suspend (String, ExecOrigin)       -> Unit
    ) {
        val genId = ++currentGenId
        cancelled.set(false)

        RuntimeEventLog.post(
            "ORCHESTRATOR", EventSeverity.INFO,
            "gen#$genId EXECUTE ${request.queryType.name} tokens=${request.maxTokens} " +
            "mode=${prefs.effectiveMode.name}"
        )

        // ── Step 1: Route ──────────────────────────────────────────────────────
        val decision = router.route(request, context)

        if (cancelled.get()) {
            onError("Cancelled before execution", ExecOrigin.NONE)
            return
        }

        // ── Step 2: Privacy gate ───────────────────────────────────────────────
        // Only runs when primary is cloud. Local backend bypasses entirely.
        val effectiveRequest = if (decision.primary.origin == ExecOrigin.CLOUD ||
            decision.primary.origin == ExecOrigin.HYBRID) {
            when (val guardResult = PrivacyGuard.evaluate(
                request      = request,
                privacyLevel = prefs.privacyLevel,
                execMode     = prefs.effectiveMode
            )) {
                is SanitizationResult.Blocked -> {
                    // Privacy blocked cloud → fall back to local if available
                    val localFallback = decision.fallbacks
                        .firstOrNull { it.origin == ExecOrigin.LOCAL }
                    if (localFallback != null && localFallback.isAvailable) {
                        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                            "gen#$genId Privacy blocked cloud → falling back to local")
                        executeBackend(genId, localFallback, request, onToken, onComplete, onError)
                        return
                    } else {
                        onError(
                            "Privacy settings block this request from reaching the cloud, " +
                            "and no local model is available.",
                            ExecOrigin.NONE
                        )
                        return
                    }
                }
                is SanitizationResult.Allowed -> {
                    if (guardResult.wasSanitized) {
                        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
                            "gen#$genId Prompt sanitized: stripped=${guardResult.strippedItems}")
                    }
                    guardResult.sanitized
                }
            }
        } else {
            request
        }

        // ── Step 3: Execute primary, then fallbacks ────────────────────────────
        val allBackends = decision.allBackends
        var lastError   = "Unknown error"
        var lastOrigin  = decision.primary.origin

        for (backend in allBackends) {
            if (cancelled.get()) {
                onError("Cancelled during execution", lastOrigin)
                return
            }
            if (!backend.isAvailable) {
                RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                    "gen#$genId Skipping ${backend.id} — not available")
                continue
            }

            val req = if (backend.origin == ExecOrigin.CLOUD) effectiveRequest else request
            var backendSucceeded = false

            RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
                "gen#$genId Attempting backend=${backend.id}")

            backend.generateStream(
                request    = req,
                onToken    = { token ->
                    // Stale-stream guard: drop tokens from superseded generations.
                    if (genId == currentGenId && !cancelled.get()) {
                        onToken(token)
                    }
                },
                onComplete = { fullText, latencyMs ->
                    if (genId == currentGenId) {
                        backendSucceeded = true
                        prefs.recordCloudTokens(
                            if (backend.origin == ExecOrigin.CLOUD)
                                fullText.length / 4 else 0
                        )
                        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
                            "gen#$genId ${backend.id} complete latency=${latencyMs}ms")
                        onComplete(fullText, latencyMs, backend.origin)
                    }
                },
                onError    = { error ->
                    lastError  = error
                    lastOrigin = backend.origin
                    RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                        "gen#$genId ${backend.id} failed: ${error.take(80)}")
                }
            )

            if (backendSucceeded) return  // Done — don't try fallbacks.
        }

        // All backends exhausted.
        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.ERROR,
            "gen#$genId All backends failed. Last: $lastError")
        onError(lastError, lastOrigin)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun executeBackend(
        genId:      Long,
        backend:    RuntimeBackend,
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long, ExecOrigin) -> Unit,
        onError:    suspend (String, ExecOrigin) -> Unit
    ) {
        var succeeded = false
        backend.generateStream(
            request    = request,
            onToken    = { token ->
                if (genId == currentGenId && !cancelled.get()) onToken(token)
            },
            onComplete = { fullText, latencyMs ->
                if (genId == currentGenId) {
                    succeeded = true
                    onComplete(fullText, latencyMs, backend.origin)
                }
            },
            onError    = { error ->
                onError(error, backend.origin)
            }
        )
    }

    companion object {
        private const val TAG = "AIRI_HybridOrchestrator"
    }
}
