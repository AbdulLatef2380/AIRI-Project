package com.airi.assistant.execution.cloud

/**
 * Normalized cloud error classification.
 *
 * Used across all provider adapters so [HybridOrchestrator] and
 * [RoutingPolicy] can make failover decisions based on error semantics
 * rather than raw HTTP codes.
 *
 * Retryability guide (checked in [CloudErrorMapper]):
 *  - RATE_LIMITED        → retryable after backoff
 *  - SERVER_ERROR        → retryable after backoff
 *  - TIMEOUT             → retryable once
 *  - CONNECTION_LOST     → retryable once
 *  - UNAUTHORIZED        → NOT retryable (bad key)
 *  - QUOTA_EXCEEDED      → NOT retryable (billing limit)
 *  - CONTENT_FILTERED    → NOT retryable (policy)
 *  - INVALID_REQUEST     → NOT retryable (malformed prompt)
 *  - CONTEXT_LENGTH      → NOT retryable (reduce prompt)
 *  - CANCELLED           → NOT retryable (user-initiated)
 *  - UNKNOWN             → not retryable by default
 */
enum class CloudErrorType {
    RATE_LIMITED,       // 429 — requests-per-minute exceeded, retry after delay
    QUOTA_EXCEEDED,     // 429 with billing signal or 402 — daily/monthly limit
    UNAUTHORIZED,       // 401 / 403 — invalid or missing API key
    SERVER_ERROR,       // 5xx — transient provider failure
    TIMEOUT,            // Network or read timeout
    CONNECTION_LOST,    // Mid-stream TCP disconnect (httpCode = -2)
    CONTENT_FILTERED,   // Provider refused generation (safety policy)
    INVALID_REQUEST,    // 400 — malformed request body
    CONTEXT_LENGTH,     // 400 — prompt exceeds the model's context window
    CANCELLED,          // Coroutine was cancelled before / during the request
    UNKNOWN             // Unrecognized error — treat as non-retryable
}
