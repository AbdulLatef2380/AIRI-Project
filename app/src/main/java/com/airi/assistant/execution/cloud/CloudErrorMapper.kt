package com.airi.assistant.execution.cloud

/**
 * Pure-function mapper from raw HTTP codes and error bodies to normalized
 * [CloudErrorType] values with retryability flags.
 *
 * All string comparisons are case-insensitive. Response bodies are used only
 * for local sub-classification within ambiguous HTTP codes (e.g. 400, 429);
 * no body content is returned because mapped messages reach diagnostics and UI.
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
        httpCode == 401 || httpCode == 403      -> MappedError(CloudErrorType.UNAUTHORIZED,     false, "Authentication or permission failed (HTTP $httpCode) — check credentials")
        httpCode == 402                         -> MappedError(CloudErrorType.QUOTA_EXCEEDED,   false, "Billing limit exceeded")
        httpCode == 404                         -> MappedError(CloudErrorType.MODEL_NOT_FOUND,  false, "Model or provider endpoint was not found")
        httpCode == 408                         -> MappedError(CloudErrorType.TIMEOUT,          true,  "Provider request timed out (HTTP 408)")
        httpCode == 429                         -> classify429(body)
        httpCode == 504                         -> MappedError(CloudErrorType.TIMEOUT,          true,  "Provider gateway timed out (HTTP 504)")
        httpCode >= 500                         -> MappedError(CloudErrorType.SERVER_ERROR,     true,  "Provider server error (HTTP $httpCode)")
        httpCode == 400 && body.hasContextErr   -> MappedError(CloudErrorType.CONTEXT_LENGTH,  false, "Prompt exceeds model context window")
        httpCode == 400 && body.hasSafetyErr    -> MappedError(CloudErrorType.CONTENT_FILTERED, false, "Content policy violation")
        httpCode == 400                         -> MappedError(CloudErrorType.INVALID_REQUEST,  false, "Provider rejected the request (HTTP 400)")
        httpCode in 200..299 && body.hasContextErr -> MappedError(CloudErrorType.CONTEXT_LENGTH, false, "Prompt exceeds model context window")
        httpCode in 200..299 && body.hasSafetyErr -> MappedError(CloudErrorType.CONTENT_FILTERED, false, "Content policy violation")
        httpCode in 200..299 && body.containsAny("overload", "temporarily unavailable", "try again") ->
            MappedError(CloudErrorType.SERVER_ERROR, true, "Provider is temporarily unavailable")
        httpCode in 200..299                    -> MappedError(CloudErrorType.UNKNOWN,          false, "Unexpected success code in error path: $httpCode")
        else                                    -> MappedError(CloudErrorType.UNKNOWN,          false, "Provider request failed (HTTP $httpCode)")
    }

    /** Classifies structured provider envelopes without searching arbitrary text. */
    fun mapStructuredProviderError(
        code: String?,
        type: String? = null,
        status: String? = null,
    ): MappedError {
        val values = listOfNotNull(code, type, status)
            .map { it.trim().lowercase().replace('-', '_').replace(' ', '_') }
        return when {
            values.any { it in setOf("insufficient_quota", "quota_exceeded", "billing_limit", "402") } ->
                MappedError(CloudErrorType.QUOTA_EXCEEDED, false, "Cloud quota exhausted — upgrade billing or wait for reset")
            values.any { it in setOf("rate_limit_exceeded", "rate_limit_error", "resource_exhausted", "429") } ->
                MappedError(CloudErrorType.RATE_LIMITED, true, "Rate limited — retry after a short delay")
            values.any { it in setOf("invalid_api_key", "authentication_error", "unauthenticated", "permission_denied", "unauthorized", "401", "403") } ->
                MappedError(CloudErrorType.UNAUTHORIZED, false, "Authentication or permission failed — check credentials")
            values.any { it in setOf("model_not_found", "not_found", "404") } ->
                MappedError(CloudErrorType.MODEL_NOT_FOUND, false, "Model or provider endpoint was not found")
            values.any { it in setOf("context_length_exceeded", "maximum_context_length_exceeded", "context_length", "413") } ->
                MappedError(CloudErrorType.CONTEXT_LENGTH, false, "Prompt exceeds model context window")
            values.any { it in setOf("content_filter", "content_policy_violation", "safety", "prohibited_content") } ->
                MappedError(CloudErrorType.CONTENT_FILTERED, false, "Content policy violation")
            values.any { it in setOf("invalid_argument", "invalid_request_error", "invalid_request", "400") } ->
                MappedError(CloudErrorType.INVALID_REQUEST, false, "Provider rejected the request")
            values.any { it in setOf("unavailable", "internal", "server_error", "500", "503") } ->
                MappedError(CloudErrorType.SERVER_ERROR, true, "Provider is temporarily unavailable")
            else -> MappedError(CloudErrorType.UNKNOWN, false, "Provider returned an unrecognized error")
        }
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
