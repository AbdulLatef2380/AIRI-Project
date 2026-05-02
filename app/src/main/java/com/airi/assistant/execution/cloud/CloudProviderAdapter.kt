package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest

/**
 * Contract for a production-grade cloud provider streaming adapter.
 *
 * Each implementation handles ONE provider's wire protocol completely:
 *  - Authentication (API key injection, never logged)
 *  - Request serialization (provider-specific JSON schema)
 *  - SSE / chunked-response parsing
 *  - Accurate token counting from the `usage` field in the final chunk
 *  - Cooperative cancellation (disconnect on coroutine cancel via finally block)
 *  - Normalized error mapping via [CloudErrorMapper]
 *  - Retry decisions (retryable flag on every failure)
 *
 * ## Threading
 * [streamGenerate] suspends and runs internal I/O on [Dispatchers.IO].
 * Callers do NOT need to switch dispatchers. [onToken] may be called from
 * [Dispatchers.IO] — callers must not touch Compose state directly.
 *
 * ## Cancellation
 * When the enclosing coroutine is cancelled, [streamGenerate] returns a
 * [AdapterResult.Failure] with [CloudErrorType.CANCELLED]. The HTTP connection
 * is always disconnected in a `finally` block, releasing the TCP socket
 * immediately even on mid-stream cancellation.
 */
interface CloudProviderAdapter {

    /** Machine-readable provider ID (e.g. "gemini", "openai", "openrouter"). */
    val providerId: String

    /**
     * True when an API key is present AND network access is permitted.
     * This is a cheap local check — it does NOT perform a network ping.
     */
    val isAvailable: Boolean

    /**
     * Open a streaming request, delivering tokens via [onToken].
     *
     * @param request   Fully-formed execution request.
     * @param onToken   Called for each decoded token (may be on IO thread).
     * @param onUsage   Called once at stream end with accurate token counts.
     *                  (promptTokens, completionTokens) — both 0 if provider
     *                  does not report usage in streaming mode.
     * @return [AdapterResult.Success] or [AdapterResult.Failure] with
     *         a normalized [CloudErrorType].
     */
    suspend fun streamGenerate(
        request:  ExecutionRequest,
        onToken:  suspend (String) -> Unit,
        onUsage:  suspend (promptTokens: Int, completionTokens: Int) -> Unit = { _, _ -> }
    ): AdapterResult

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class AdapterResult {

        data class Success(
            val fullText:         String,
            val latencyMs:        Long,
            val promptTokens:     Int = 0,
            val completionTokens: Int = 0
        ) : AdapterResult() {
            val totalTokens: Int get() = promptTokens + completionTokens
        }

        data class Failure(
            val error:     String,
            val errorType: CloudErrorType,
            val retryable: Boolean,
            val httpCode:  Int = -1
        ) : AdapterResult()
    }
}
