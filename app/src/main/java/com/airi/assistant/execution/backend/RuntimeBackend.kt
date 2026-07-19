package com.airi.assistant.execution.backend

import com.airi.assistant.execution.CapabilityProfile
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecutionResult

/**
 * Contract every runtime backend must satisfy.
 *
 * Threading: [generateStream] may be called from any dispatcher; backends
 * dispatch internally to the appropriate thread (llamaDispatcher / IO).
 *
 * Cancellation: [cancelStream] is non-blocking and lock-free. It propagates
 * the cancel signal to the underlying engine (native flag, HTTP close, etc.)
 * within one processing unit (chunk / network read), bounding cancel latency.
 *
 * Error handling: errors are surfaced via [onError], never thrown from
 * [generateStream]. Exactly one of [onComplete] or [onError] is guaranteed.
 */
interface RuntimeBackend {

    val id: String
    val displayName: String
    val capabilities: CapabilityProfile
    val origin: ExecOrigin
    val isAvailable: Boolean

    /**
     * Signal cancellation of any in-flight [generateStream] call.
     * Non-blocking, lock-free. Default no-op for backends without extra cancel mechanism.
     */
    fun cancelStream() {}

    suspend fun generateStream(
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long) -> Unit,
        onError:    suspend (String) -> Unit
    )

    suspend fun generate(request: ExecutionRequest): ExecutionResult
}
