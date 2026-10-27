package com.airi.assistant.execution

/** Normalized provider termination reason kept separate from user-facing errors. */
enum class CompletionReason {
    STOP,
    LENGTH_LIMIT,
    CONTENT_FILTER,
    TOOL_CALLS,
    CANCELLED,
    ERROR,
    CONNECTION_LOST,
    UNKNOWN,
}

/**
 * Provider-neutral completion evidence.
 *
 * [rawReason] is retained for diagnostics and provider-specific behavior, but
 * must never contain credentials or response content. The normalized [reason]
 * is what AgentLoop and UI policy should consume.
 */
data class CompletionMetadata(
    val reason: CompletionReason = CompletionReason.UNKNOWN,
    val rawReason: String? = null,
    val providerId: String = "",
    val terminal: Boolean = true,
) {
    companion object {
        fun fromProvider(providerId: String, rawReason: String?): CompletionMetadata {
            val normalized = rawReason?.trim()?.uppercase().orEmpty()
            val reason = when (normalized) {
                "STOP", "END_TURN", "COMPLETED", "DONE", "MESSAGE_STOP", "LOCAL_NATIVE_COMPLETE" -> CompletionReason.STOP
                "LENGTH", "MAX_TOKENS", "MAX_OUTPUT_TOKENS", "TOKEN_LIMIT", "MODEL_LENGTH" -> CompletionReason.LENGTH_LIMIT
                "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "CONTENT_FILTER", "SPII", "IMAGE_SAFETY" -> CompletionReason.CONTENT_FILTER
                "TOOL_CALLS", "FUNCTION_CALL", "TOOL_USE" -> CompletionReason.TOOL_CALLS
                "CANCELLED", "CANCELED" -> CompletionReason.CANCELLED
                "ERROR", "FAILED" -> CompletionReason.ERROR
                "CONNECTION_LOST", "DISCONNECTED", "EOF", "NETWORK_ERROR" -> CompletionReason.CONNECTION_LOST
                else -> CompletionReason.UNKNOWN
            }
            return CompletionMetadata(reason, rawReason?.takeIf { it.isNotBlank() }, providerId)
        }
    }
}
