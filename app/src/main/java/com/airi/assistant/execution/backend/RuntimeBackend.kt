package com.airi.assistant.execution.backend

import com.airi.assistant.execution.CapabilityProfile
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecutionResult

/**
 * Contract that every runtime backend must satisfy.
 *
 * Backends are registered with [RuntimeRouter] and selected based on
 * their [capabilities] matching the incoming [ExecutionRequest].
 *
 * ## Lifecycle
 *  - Backends are stateless after construction: they hold references to
 *    the underlying engine (LlamaManager, RemoteModelExecutor, etc.) but
 *    do not own coroutine scopes.
 *  - All suspend functions must be cancellation-cooperative. If the caller's
 *    coroutine is cancelled, the backend must propagate cancellation to any
 *    ongoing I/O or native call within 250 ms.
 *
 * ## Threading
 *  - [generateStream] is called from whatever dispatcher the orchestrator
 *    decides. Backends must dispatch internally to the correct dispatcher
 *    (e.g. llamaDispatcher for local inference, IO for HTTP calls).
 *  - [isAvailable] must be a fast, non-blocking check (no I/O).
 *
 * ## Error handling
 *  - Backends surface errors through [onError], never by throwing from
 *    [generateStream]. This matches the existing LlamaManager contract.
 *  - [generate] may throw or return [ExecutionResult.Failure]; callers
 *    wrap in runCatching.
 */
interface RuntimeBackend {

    /** Stable identifier for logging and routing decisions. */
    val id: String

    /** Human-readable label shown in the UI execution origin badge. */
    val displayName: String

    /** Capability profile used by the router for backend selection. */
    val capabilities: CapabilityProfile

    /** Origin tag that will be attached to every response from this backend. */
    val origin: ExecOrigin

    /**
     * Whether this backend is currently in a state where it can accept requests.
     *
     * For the local backend: model is loaded and engine is idle.
     * For the cloud backend: a provider is configured and network appears reachable.
     *
     * This is a best-effort check — the router uses it as a hint, but a backend
     * that reports [isAvailable] = true may still fail on the actual call.
     */
    val isAvailable: Boolean

    /**
     * Streaming generation — preferred for interactive chat turns.
     *
     * [onToken] is called for each incremental token, [onComplete] when the
     * full response has been delivered, [onError] on any failure.
     *
     * [onComplete] receives the full accumulated text and wall-clock latency.
     * [onError]   receives a human-readable error string.
     *
     * The backend guarantees exactly one of [onComplete] or [onError] is called,
     * even if the generation is cancelled (in which case [onError] is called).
     */
    suspend fun generateStream(
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long) -> Unit,
        onError:    suspend (String)       -> Unit
    )

    /**
     * Batch (non-streaming) generation — used by tool pipelines and tests.
     * Returns an [ExecutionResult] rather than routing through callbacks.
     */
    suspend fun generate(request: ExecutionRequest): ExecutionResult
}
