package com.airi.assistant.ui.viewmodel

import java.util.Locale

enum class ExecutionFailureStage {
    PREPARATION,
    AGENT_LOOP,
    PROVIDER,
    RESPONSE,
    ATTACHMENT
}

enum class ExecutionFailureKind {
    PROVIDER_CREDENTIALS,
    PROVIDER_MODEL_UNAVAILABLE,
    PROVIDER_QUOTA_EXHAUSTED,
    PROVIDER_REJECTED,
    PROVIDER_CONTEXT_LIMIT,
    PROVIDER_CONTENT_FILTERED,
    PROVIDER_RATE_LIMIT,
    PROVIDER_TIMEOUT,
    PROVIDER_UNAVAILABLE,
    AGENT_LOOP_FAILED,
    RESPONSE_FAILED
}

data class ExecutionFailureClassification(
    val kind: ExecutionFailureKind,
    val stage: ExecutionFailureStage
)

/** Maps backend diagnostics to a safe, localized UI category without exposing provider prose. */
object ExecutionFailurePolicy {
    fun classify(rawMessage: String?, responseStarted: Boolean): ExecutionFailureClassification {
        val message = rawMessage.orEmpty().lowercase(Locale.ROOT)
        return when {
            listOf("invalid api key", "unauthorized", "http 401", "status 401")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_CREDENTIALS,
                ExecutionFailureStage.PROVIDER
            )
            listOf("model is not available", "model not found", "http 404", "status 404")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_MODEL_UNAVAILABLE,
                ExecutionFailureStage.PROVIDER
            )
            listOf("quota exhausted", "quota exceeded", "billing quota")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_QUOTA_EXHAUSTED,
                ExecutionFailureStage.PROVIDER
            )
            listOf("context window", "context length", "prompt exceeds", "prompt too long", "too many tokens")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_CONTEXT_LIMIT,
                ExecutionFailureStage.PROVIDER
            )
            listOf("content policy", "safety", "filtered", "content_filter")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_CONTENT_FILTERED,
                ExecutionFailureStage.PROVIDER
            )
            listOf("rate limit", "rate-limiting", "rate limiting", "too many requests", "http 429", "status 429")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_RATE_LIMIT,
                ExecutionFailureStage.PROVIDER
            )
            listOf("timed out", "timeout", "deadline exceeded")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_TIMEOUT,
                ExecutionFailureStage.PROVIDER
            )
            listOf("rejected this request", "rejected the request", "invalid_request", "http 400")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_REJECTED,
                ExecutionFailureStage.PROVIDER
            )
            listOf("server error", "unexpected error", "http 5", "network", "connection")
                .any(message::contains) -> ExecutionFailureClassification(
                ExecutionFailureKind.PROVIDER_UNAVAILABLE,
                ExecutionFailureStage.PROVIDER
            )
            responseStarted -> ExecutionFailureClassification(
                ExecutionFailureKind.RESPONSE_FAILED,
                ExecutionFailureStage.RESPONSE
            )
            else -> ExecutionFailureClassification(
                ExecutionFailureKind.AGENT_LOOP_FAILED,
                ExecutionFailureStage.AGENT_LOOP
            )
        }
    }
}
