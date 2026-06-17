package com.airi.assistant.execution

import android.content.Context
import android.util.Log
import com.airi.assistant.core.debug.EventSeverity
import com.airi.assistant.core.debug.RuntimeEventLog
import com.airi.assistant.execution.backend.RuntimeBackend
import com.airi.assistant.execution.diagnostics.ExecTransitionEvent
import com.airi.assistant.execution.diagnostics.ExecutionDiagnosticsState
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.execution.privacy.SanitizationResult
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.execution.router.RuntimeRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Top-level entry point for the Hybrid Execution layer — production-hardened.
 *
 * ## Execution ownership (ONE active owner at a time)
 * A [Mutex] serializes [executeStream] calls. If a new request arrives while
 * one is running, the previous call's coroutine must already have been
 * cancelled at the call site ([ChatViewModel]) before [executeStream] is
 * re-entered. The Mutex ensures the new call waits for all cleanup of the
 * previous call to complete before acquiring ownership.
 *
 * ## Stale-stream guard (generation counter)
 * Each [executeStream] call atomically increments [currentGenId].
 * Token callbacks check `genId == currentGenId` before dispatching to UI.
 * If a slow cancel allows two concurrent calls (which should not happen
 * given the Mutex), the stale call's tokens are silently dropped.
 *
 * ## Cancellation
 * [cancel] sets [cancelled] and is safe to call from any thread/dispatcher.
 * The Mutex is NOT held by [cancel] — cancellation must be non-blocking.
 * Structural cancellation (coroutine scope cancel) is the primary mechanism;
 * [cancelled] is a belt-and-suspenders safety net.
 *
 * ## Privacy gate
 * Cloud-bound requests pass through [PrivacyGuard] before reaching the
 * adapter. MAXIMUM privacy routes to local; BALANCED sanitizes the prompt.
 *
 * ## Failover
 * Primary backend failure triggers sequential fallback through
 * [RuntimeRouter.RoutingDecision.fallbacks]. Each failover is logged to
 * [RuntimeEventLog] and recorded in [execDiagnostics].
 *
 * ## Observability
 * [execDiagnostics] is a [StateFlow] of [ExecutionDiagnosticsState] updated
 * at every significant lifecycle event (start, token, complete, error, cancel,
 * fallback, retry).
 */
class HybridOrchestrator(
    private val router: RuntimeRouter,
    private val prefs:  ExecModePreferences
) {

    // ── Execution ownership ───────────────────────────────────────────────────

    /**
     * Mutex that serializes [executeStream] calls.
     * ONE active execution owner at a time — no concurrent streams.
     */
    private val executionLock = Mutex()

    /** Monotonically increasing generation ID for stale-token detection. */
    private val currentGenId = AtomicLong(0L)

    /** Belt-and-suspenders cancellation flag. Thread-safe. */
    private val cancelled = AtomicBoolean(false)

    // ── Observability ─────────────────────────────────────────────────────────

    private val _execDiagnostics = MutableStateFlow(ExecutionDiagnosticsState())
    val execDiagnostics: StateFlow<ExecutionDiagnosticsState> = _execDiagnostics.asStateFlow()

    // ── Session counters (cumulative) ─────────────────────────────────────────
    private var sessionRetryCount        = 0
    private var sessionFallbackCount     = 0
    private var sessionCancellationCount = 0
    private val transitionHistory        = ArrayDeque<ExecTransitionEvent>(MAX_HISTORY)

    // ── Cancellation API ──────────────────────────────────────────────────────

    /**
     * Signal cancellation of any in-flight execution.
     * Thread-safe. Does NOT acquire [executionLock].
     */
    fun cancel() {
        cancelled.set(true)
        Log.i(TAG, "cancel() called — genId=${currentGenId.get()}")
        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN, "Cancel requested")
        updateDiagnostics { copy(isStreaming = false, lastCancelReason = "User cancel") }
    }

    /** Clear cancel flag before starting a new generation. */
    fun resetCancel() { cancelled.set(false) }

    // ── Primary API ───────────────────────────────────────────────────────────

    /**
     * Execute a request end-to-end: route → privacy gate → primary backend
     * → automatic fallback → origin-tagging.
     *
     * [executeStream] is serialized by [executionLock]. Concurrent calls
     * queue and execute sequentially (the previous must be cancelled first
     * by the call site for normal interactive chat).
     *
     * @param request     Fully-formed execution request.
     * @param context     Android Context for device signals.
     * @param onToken     Per-token callback (may fire from IO thread).
     * @param onComplete  Called exactly once on success: (fullText, latencyMs, origin).
     * @param onError     Called exactly once when ALL backends fail: (message, origin).
     */
    suspend fun executeStream(
        request:    ExecutionRequest,
        context:    Context,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long, ExecOrigin) -> Unit,
        onError:    suspend (String, ExecOrigin)       -> Unit
    ) = executionLock.withLock {
        val genId = currentGenId.incrementAndGet()
        cancelled.set(false)

        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
            "gen#$genId EXECUTE ${request.queryType.name} mode=${prefs.effectiveMode.name} " +
            "tokens_est=${request.estimatedPromptTokens}")

        updateDiagnostics { copy(isStreaming = true, activeBackend = "routing") }

        // ── Step 1: Route ──────────────────────────────────────────────────
        val decision = router.route(request, context)

        if (cancelled.get()) {
            sessionCancellationCount++
            updateDiagnostics { copy(isStreaming = false, lastCancelReason = "Cancelled after routing") }
            onError("Cancelled before execution", ExecOrigin.NONE)
            return@withLock
        }

        // ── Step 2: Privacy gate (cloud-bound requests only) ───────────────
        val privacyGateResult = applyPrivacyGate(genId, request, decision)
        if (privacyGateResult == null) {
            // Privacy gate forced local fallback — handled inside applyPrivacyGate
            val localFallback = decision.fallbacks.firstOrNull { it.origin == ExecOrigin.LOCAL }
            if (localFallback != null && localFallback.isAvailable) {
                dispatchToBackend(genId, localFallback, request, onToken, onComplete, onError)
            } else {
                updateDiagnostics { copy(isStreaming = false) }
                onError(
                    "Privacy settings block this request from cloud, and no local model is loaded.",
                    ExecOrigin.NONE
                )
            }
            return@withLock
        }
        val effectiveRequest = privacyGateResult

        // ── Step 3: Execute primary → fallbacks ───────────────────────────
        val allBackends = decision.allBackends
        var lastError   = "Unknown error"
        var lastOrigin  = decision.primary.origin

        for ((idx, backend) in allBackends.withIndex()) {
            if (cancelled.get()) {
                sessionCancellationCount++
                updateDiagnostics { copy(
                    isStreaming = false,
                    cancellationCount = sessionCancellationCount,
                    lastCancelReason = "Cancelled during execution on ${backend.id}"
                )}
                onError("Cancelled", lastOrigin)
                return@withLock
            }

            if (!backend.isAvailable) {
                RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                    "gen#$genId Skipping ${backend.id} — unavailable")
                continue
            }

            val isFallback  = idx > 0
            val req         = if (backend.origin == ExecOrigin.CLOUD) effectiveRequest else request

            if (isFallback) {
                sessionFallbackCount++
                val prevId = allBackends[idx - 1].id
                recordTransition(prevId, backend.id, lastError, backend.origin)
                RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                    "gen#$genId FALLBACK ${allBackends[idx-1].id} → ${backend.id} reason=${lastError.take(60)}")
                updateDiagnostics { copy(
                    fallbackCount     = sessionFallbackCount,
                    lastFallbackFrom  = allBackends[idx - 1].id,
                    lastFallbackTo    = backend.id,
                    lastFallbackReason = lastError.take(80)
                )}
                // B-07: Post a user-visible activity event so ChatScreen can show a
                // non-intrusive notice that cloud failed and local model is being used.
                if (allBackends[idx - 1].origin == com.airi.assistant.execution.ExecOrigin.CLOUD
                    && backend.origin == com.airi.assistant.execution.ExecOrigin.LOCAL) {
                    com.airi.assistant.ui.activity.AgentActivityBus.emit(
                        com.airi.assistant.ui.activity.ActivityEvent(
                            message  = "Cloud model unavailable — using local model",
                            detail   = lastError.take(80),
                            category = com.airi.assistant.ui.activity.ActivityCategory.ROUTING,
                            severity = com.airi.assistant.ui.activity.ActivitySeverity.WARN
                        )
                    )
                }
            }

            updateDiagnostics { copy(
                activeBackend  = backend.id,
                activeProvider = null,
                activeOrigin   = backend.origin
            )}

            RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
                "gen#$genId → ${backend.id} (attempt ${idx + 1}/${allBackends.size})")

            var backendSucceeded = false
            val streamStart      = System.currentTimeMillis()

            backend.generateStream(
                request    = req,
                onToken    = { token ->
                    if (genId == currentGenId.get() && !cancelled.get()) {
                        onToken(token)
                    }
                },
                onComplete = { fullText, latencyMs ->
                    if (genId == currentGenId.get()) {
                        backendSucceeded = true
                        updateDiagnostics { copy(
                            isStreaming          = false,
                            lastStreamDurationMs = System.currentTimeMillis() - streamStart,
                            lastProviderLatencyMs = latencyMs
                        )}
                        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
                            "gen#$genId ${backend.id} OK latency=${latencyMs}ms")
                        onComplete(fullText, latencyMs, backend.origin)
                    }
                },
                onError    = { error ->
                    lastError  = error
                    lastOrigin = backend.origin
                    RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                        "gen#$genId ${backend.id} failed: ${error.take(80)}")
                    updateDiagnostics { copy(lastErrorMessage = error.take(100)) }
                }
            )

            if (backendSucceeded) return@withLock
        }

        // All backends exhausted.
        updateDiagnostics { copy(isStreaming = false, lastErrorMessage = lastError) }
        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.ERROR,
            "gen#$genId All backends failed. Last: ${lastError.take(80)}")
        onError(lastError, lastOrigin)
    }

    // ── Privacy gate ──────────────────────────────────────────────────────────

    /**
     * Apply privacy guard to cloud-bound requests.
     * Returns the (possibly sanitized) request, or null if privacy blocks cloud
     * and there is no local fallback available (caller handles null).
     */
    private suspend fun applyPrivacyGate(
        genId:    Long,
        request:  ExecutionRequest,
        decision: RuntimeRouter.RoutingDecision
    ): ExecutionRequest? {
        val primaryIsCloud = decision.primary.origin == ExecOrigin.CLOUD ||
                             decision.primary.origin == ExecOrigin.HYBRID
        if (!primaryIsCloud) return request   // local-bound: no gate needed

        return when (val guardResult = PrivacyGuard.evaluate(
            request      = request,
            privacyLevel = prefs.privacyLevel,
            execMode     = prefs.effectiveMode
        )) {
            is SanitizationResult.Blocked -> {
                RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                    "gen#$genId Privacy blocked cloud dispatch (level=${prefs.privacyLevel.name})")
                null   // Caller routes to local fallback
            }
            is SanitizationResult.Allowed -> {
                if (guardResult.wasSanitized) {
                    RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
                        "gen#$genId Prompt sanitized: stripped=${guardResult.strippedItems}")
                }
                guardResult.sanitized
            }
        }
    }

    // ── Backend dispatch helper ───────────────────────────────────────────────

    private suspend fun dispatchToBackend(
        genId:      Long,
        backend:    RuntimeBackend,
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long, ExecOrigin) -> Unit,
        onError:    suspend (String, ExecOrigin)       -> Unit
    ) {
        updateDiagnostics { copy(activeBackend = backend.id, activeOrigin = backend.origin) }
        backend.generateStream(
            request    = request,
            onToken    = { token -> if (genId == currentGenId.get() && !cancelled.get()) onToken(token) },
            onComplete = { fullText, latencyMs ->
                if (genId == currentGenId.get()) {
                    updateDiagnostics { copy(isStreaming = false, lastStreamDurationMs = latencyMs) }
                    onComplete(fullText, latencyMs, backend.origin)
                }
            },
            onError    = { error ->
                updateDiagnostics { copy(isStreaming = false, lastErrorMessage = error.take(100)) }
                onError(error, backend.origin)
            }
        )
    }

    // ── Transition history ────────────────────────────────────────────────────

    private fun recordTransition(from: String, to: String, reason: String, origin: ExecOrigin) {
        val event = ExecTransitionEvent(
            timestampMs = System.currentTimeMillis(),
            fromBackend = from,
            toBackend   = to,
            reason      = reason.take(80),
            origin      = origin
        )
        if (transitionHistory.size >= MAX_HISTORY) transitionHistory.removeFirst()
        transitionHistory.addLast(event)
        updateDiagnostics { copy(transitionHistory = transitionHistory.toList()) }
    }

    // ── Diagnostics helper ────────────────────────────────────────────────────

    private fun updateDiagnostics(update: ExecutionDiagnosticsState.() -> ExecutionDiagnosticsState) {
        _execDiagnostics.value = _execDiagnostics.value.update()
    }

    companion object {
        private const val TAG         = "AIRI_HybridOrchestrator"
        private const val MAX_HISTORY = 20
    }
}
