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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Owns the active generation and rejects stale or cancelled callbacks. */
    private val generationGate = ExecutionGenerationGate()

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
    /** Tracks the currently executing backend so cancel() can reach its native cancel path. */
    @Volatile private var activeBackend_: RuntimeBackend? = null

    /**
     * Signal cancellation. Propagates to the active backend's own cancellation
     * mechanism — for LocalLlamaBackend this reaches LlamaNative.nativeCancel()
     * within one llama_decode chunk, preventing unbounded JNI blocking.
     */
    fun cancel() {
        generationGate.cancel()
        val backend = activeBackend_
        if (backend != null) {
            Log.i(TAG, "cancel() → ${backend.id} genId=${generationGate.currentGenerationId()}")
            runCatching { backend.cancelStream() }
                .onFailure { e -> Log.w(TAG, "cancelStream threw: ${e.message}") }
        } else {
            Log.i(TAG, "cancel() — no active backend genId=${generationGate.currentGenerationId()}")
        }
        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN, "Cancel requested")
        updateDiagnostics { copy(isStreaming = false, lastCancelReason = "User cancel") }
    }

    /** Clear cancel flag before starting a new generation. */
    fun resetCancel() = generationGate.resetCancel()

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
        val genId = generationGate.beginGeneration()

        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO,
            "gen#$genId EXECUTE ${request.queryType.name} mode=${prefs.effectiveMode.name} " +
            "tokens_est=${request.estimatedPromptTokens}")

        updateDiagnostics { copy(isStreaming = true, activeBackend = "routing") }

        // ── Step 1: Route ──────────────────────────────────────────────────
        val decision = router.route(request, context)

        if (generationGate.isCancelled()) {
            throw generationCancelled("after routing")
        }

        // ── Step 2: Evaluate one sanitized cloud copy for every cloud attempt ──
        // The primary backend can be local while a cloud backend appears later as a
        // fallback. Prepare the cloud copy once, then skip blocked cloud attempts
        // without preventing a local primary from running.
        val cloudRequest = if (decision.allBackends.any { it.origin.isCloudBound() }) {
            applyPrivacyGate(genId, request)
        } else {
            request
        }

        // ── Step 3: Execute primary → fallbacks ───────────────────────────
        val allBackends = decision.allBackends
        if (!decision.signals.networkAvailable &&
            decision.primary.origin == ExecOrigin.CLOUD &&
            decision.fallbacks.none { it.origin == ExecOrigin.LOCAL && it.isAvailable }
        ) {
            val message = "No network connection is available and no local fallback model is loaded."
            updateDiagnostics { copy(isStreaming = false, lastErrorMessage = message) }
            onError(message, ExecOrigin.NONE)
            return@withLock
        }
        var lastError   = "No eligible execution backend is available."
        var lastOrigin  = decision.primary.origin

        for ((idx, backend) in allBackends.withIndex()) {
            if (generationGate.isCancelled()) {
                throw generationCancelled("during execution on ${backend.id}")
            }

            if (!backend.isAvailable) {
                RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.WARN,
                    "gen#$genId Skipping ${backend.id} — unavailable")
                continue
            }

            if (backend.origin.isCloudBound() && cloudRequest == null) {
                RuntimeEventLog.post(
                    "ORCHESTRATOR",
                    EventSeverity.WARN,
                    "gen#$genId Skipping ${backend.id} — privacy blocks cloud dispatch"
                )
                continue
            }

            val isFallback = idx > 0
            val req = if (backend.origin.isCloudBound()) cloudRequest!! else request

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

            activeBackend_ = backend
            backend.generateStream(
                request    = req,
                onToken    = { token ->
                    if (generationGate.accepts(genId)) {
                        onToken(token)
                    }
                },
                onComplete = { fullText, latencyMs ->
                    if (generationGate.accepts(genId)) {
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

            if (generationGate.isCancelled()) {
                activeBackend_ = null
                throw generationCancelled("after backend ${backend.id}")
            }
            if (backendSucceeded) {
                activeBackend_ = null
                return@withLock
            }
            activeBackend_ = null
        }

        // All backends exhausted.
        if (generationGate.isCancelled()) throw generationCancelled("after backend attempts")
        activeBackend_ = null
        updateDiagnostics { copy(isStreaming = false, lastErrorMessage = lastError) }
        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.ERROR,
            "gen#$genId All backends failed. Last: ${lastError.take(80)}")
        onError(lastError, lastOrigin)
    }

    // ── Privacy gate ──────────────────────────────────────────────────────────

    /**
     * Prepare a sanitized request for all cloud-capable backends in the routing
     * decision. A null result means cloud is blocked; callers may still dispatch
     * local backends in the same decision.
     */
    private fun applyPrivacyGate(
        genId: Long,
        request: ExecutionRequest
    ): ExecutionRequest? = when (val guardResult = PrivacyGuard.evaluate(
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

    private fun ExecOrigin.isCloudBound(): Boolean = this == ExecOrigin.CLOUD || this == ExecOrigin.HYBRID

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
        activeBackend_ = backend
        try {
            backend.generateStream(
                request    = request,
                onToken    = { token ->
                    if (generationGate.accepts(genId)) onToken(token)
                },
                onComplete = { fullText, latencyMs ->
                    if (generationGate.accepts(genId)) {
                        updateDiagnostics { copy(isStreaming = false, lastStreamDurationMs = latencyMs) }
                        onComplete(fullText, latencyMs, backend.origin)
                    }
                },
                onError    = { error ->
                    if (!generationGate.isCancelled()) {
                        updateDiagnostics { copy(isStreaming = false, lastErrorMessage = error.take(100)) }
                        onError(error, backend.origin)
                    }
                }
            )
            if (generationGate.isCancelled()) throw generationCancelled("during privacy fallback")
        } finally {
            if (activeBackend_ === backend) activeBackend_ = null
        }
    }

    private fun generationCancelled(stage: String): CancellationException {
        sessionCancellationCount++
        activeBackend_ = null
        updateDiagnostics {
            copy(
                isStreaming = false,
                cancellationCount = sessionCancellationCount,
                lastCancelReason = "Cancelled $stage"
            )
        }
        RuntimeEventLog.post("ORCHESTRATOR", EventSeverity.INFO, "Generation cancelled $stage")
        return CancellationException("Generation cancelled $stage")
    }

    // ── Transition history ───────────────────────────────────────────────────


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
