package com.airi.assistant.execution.cloud

/**
 * Pure-function mapper from raw HTTP codes and error bodies to normalized
 * [CloudErrorType] values with retryability flags.
 *
 * All string comparisons are case-insensitive. Body snippets are used
 * only for sub-classification within ambiguous HTTP codes (e.g. 400, 429).
 *
 * ## Special sentinel codes (internal use only — never from HTTP):
 *  -1  = request timed out before server response
 *  -2  = mid-stream TCP disconnection
 *  -3  = coroutine cancelled
 */
object CloudErrorMapper {

    data class MappedError(
        val type:      CloudErrorType,
        val retryable: Boolean,
        val message:   String
    )

    fun map(httpCode: Int, body: String): MappedError = when {
        httpCode == -3                          -> MappedError(CloudErrorType.CANCELLED,        false, "Request cancelled")
        httpCode == -2                          -> MappedError(CloudErrorType.CONNECTION_LOST,  true,  "Connection lost mid-stream")
        httpCode == -1                          -> MappedError(CloudErrorType.TIMEOUT,          true,  "Request timed out")
        httpCode == 401 || httpCode == 403      -> MappedError(CloudErrorType.UNAUTHORIZED,     false, "Authentication failed (HTTP $httpCode) — check API key")
        httpCode == 402                         -> MappedError(CloudErrorType.QUOTA_EXCEEDED,   false, "Billing limit exceeded")
        httpCode == 429                         -> classify429(body)
        httpCode >= 500                         -> MappedError(CloudErrorType.SERVER_ERROR,     true,  "Provider server error (HTTP $httpCode)")
        httpCode == 400 && body.hasContextErr   -> MappedError(CloudErrorType.CONTEXT_LENGTH,  false, "Prompt exceeds model context window")
        httpCode == 400 && body.hasSafetyErr    -> MappedError(CloudErrorType.CONTENT_FILTERED, false, "Content policy violation")
        httpCode == 400                         -> MappedError(CloudErrorType.INVALID_REQUEST,  false, "Bad request: ${body.take(120)}")
        httpCode in 200..299                    -> MappedError(CloudErrorType.UNKNOWN,          false, "Unexpected success code in error path: $httpCode")
        else                                    -> MappedError(CloudErrorType.UNKNOWN,          false, "HTTP $httpCode: ${body.take(120)}")
    }

    /** 429 may be either a request-rate limit (retryable) or a quota/billing limit (not retryable). */
    private fun classify429(body: String): MappedError {
        val isQuota = body.containsAny("quota", "billing", "insufficient_quota", "exceeded your current quota")
        return if (isQuota)
            MappedError(CloudErrorType.QUOTA_EXCEEDED, false, "Cloud quota exhausted — upgrade billing or wait for reset")
        else
            MappedError(CloudErrorType.RATE_LIMITED, true, "Rate limited (429) — will retry with backoff")
    }

    // ── String helpers ────────────────────────────────────────────────────────

    private val String.hasContextErr: Boolean
        get() = containsAny(
            "context_length_exceeded", "maximum context length",
            "too many tokens", "tokens exceed", "context window"
        )

    private val String.hasSafetyErr: Boolean
        get() = containsAny(
            "content_filter", "content_policy_violation", "safety",
            "harmful", "HARM_CATEGORY", "SAFETY",        // Gemini
            "flagged", "violates our usage", "content management policy"
        )

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it, ignoreCase = true) }
}
