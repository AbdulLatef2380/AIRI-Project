package com.airi.assistant.execution

/**
 * Result of a backend execution attempt.
 *
 * Used by the backends and HybridOrchestrator for non-streaming (batch)
 * generation. Streaming generations use the callback-based interface
 * ([RuntimeBackend.generateStream]) and report their result through
 * onComplete / onError rather than through this sealed class.
 *
 * Carries the [ExecOrigin] tag so callers can label responses correctly
 * regardless of which backend produced them.
 */
sealed class ExecutionResult {

    abstract val origin: ExecOrigin

    /**
     * Generation succeeded.
     *
     * @param fullText    Complete text response from the backend.
     * @param origin      Which runtime produced this response.
     * @param latencyMs   Wall-clock time from request to last token.
     * @param tokenCount  Approximate output token count.
     * @param provider    Optional provider label (e.g. "openai", "local").
     */
    data class Success(
        val fullText:        String,
        override val origin: ExecOrigin,
        val latencyMs:       Long,
        val tokenCount:      Int    = 0,
        val provider:        String = ""
    ) : ExecutionResult()

    /**
     * Generation failed.
     *
     * @param error      Human-readable error description.
     * @param origin     Which runtime attempted and failed.
     * @param retryable  True when a fallback attempt is reasonable.
     * @param code       Machine-readable error code (e.g. "timeout",
     *                   "rate_limit", "no_network", "context_overflow").
     */
    data class Failure(
        val error:           String,
        override val origin: ExecOrigin,
        val retryable:       Boolean = true,
        val code:            String  = "unknown"
    ) : ExecutionResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}
