package com.airi.assistant.execution.cloud

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure-function mapper from provider responses to a safe, normalized error.
 * Raw bodies never leave this boundary.
 */
object CloudErrorMapper {

    data class MappedError(
        val type: CloudErrorType,
        val retryable: Boolean,
        val message: String,
        val retryAfterMs: Long? = null,
        val provider: String? = null,
        val safeCode: String = type.name,
        val needsUserAction: Boolean = !retryable && type !in setOf(
            CloudErrorType.CANCELLED,
            CloudErrorType.UNKNOWN
        )
    )

    fun map(
        httpCode: Int,
        body: String,
        retryAfterHeader: String? = null,
        provider: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): MappedError {
        val retryAfter = parseRetryAfterMs(retryAfterHeader, nowMs)
        val mapped = when {
            httpCode == -3 -> error(CloudErrorType.CANCELLED, false, "Request cancelled")
            httpCode == -2 -> error(CloudErrorType.CONNECTION_LOST, true, "Connection lost mid-stream")
            httpCode == -1 -> error(CloudErrorType.TIMEOUT, true, "Request timed out")
            httpCode == 401 || httpCode == 403 -> error(CloudErrorType.UNAUTHORIZED, false, "Authentication or permission failed — check credentials")
            httpCode == 402 -> error(CloudErrorType.QUOTA_EXCEEDED, false, "Billing limit exceeded")
            httpCode == 404 -> error(CloudErrorType.MODEL_NOT_FOUND, false, "Model or provider endpoint was not found")
            httpCode == 408 -> error(CloudErrorType.TIMEOUT, true, "Provider request timed out")
            httpCode == 429 -> classify429(body)
            httpCode == 504 -> error(CloudErrorType.TIMEOUT, true, "Provider gateway timed out")
            httpCode >= 500 -> error(CloudErrorType.TEMPORARY_OVERLOAD, true, "Provider is temporarily unavailable")
            httpCode == 400 && body.hasContextErr -> error(CloudErrorType.CONTEXT_LENGTH, false, "Prompt exceeds model context window")
            httpCode == 400 && body.hasSafetyErr -> error(CloudErrorType.CONTENT_FILTERED, false, "Content policy violation")
            httpCode == 400 && body.hasToolSchemaErr -> error(CloudErrorType.TOOL_SCHEMA_INVALID, false, "Provider rejected AIRI's tool schema; no tool was executed")
            httpCode == 400 -> error(CloudErrorType.INVALID_REQUEST, false, "Provider rejected the request")
            httpCode in 200..299 && body.hasContextErr -> error(CloudErrorType.CONTEXT_LENGTH, false, "Prompt exceeds model context window")
            httpCode in 200..299 && body.hasSafetyErr -> error(CloudErrorType.CONTENT_FILTERED, false, "Content policy violation")
            httpCode in 200..299 && body.containsAny("overload", "temporarily unavailable", "try again") ->
                error(CloudErrorType.TEMPORARY_OVERLOAD, true, "Provider is temporarily unavailable")
            httpCode in 200..299 -> error(CloudErrorType.UNKNOWN, false, "Unexpected success code in error path")
            else -> error(CloudErrorType.UNKNOWN, false, "Provider request failed")
        }
        return mapped.copy(
            retryAfterMs = if (mapped.retryable) retryAfter else null,
            provider = provider,
            safeCode = mapped.type.name,
            needsUserAction = mapped.needsUserAction,
        )
    }

    /** Classifies structured provider envelopes without searching arbitrary text. */
    fun mapStructuredProviderError(
        code: String?,
        type: String? = null,
        status: String? = null,
        retryAfterHeader: String? = null,
        provider: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): MappedError {
        val values = listOfNotNull(code, type, status)
            .map { it.trim().lowercase().replace('-', '_').replace(' ', '_') }
        val mapped = when {
            values.any { it in setOf("insufficient_quota", "quota_exceeded", "billing_limit", "402") } ->
                error(CloudErrorType.QUOTA_EXCEEDED, false, "Cloud quota exhausted — upgrade billing or wait for reset")
            values.any { it in setOf("credit_exhausted", "credits_exhausted", "out_of_credits") } ->
                error(CloudErrorType.CREDIT_EXHAUSTED, false, "Provider credits are exhausted — add credits or choose another provider")
            values.any { it in setOf("spend_limit", "budget_exceeded", "billing_hard_limit") } ->
                error(CloudErrorType.SPEND_LIMIT, false, "Provider spending limit reached — review the provider budget")
            values.any { it in setOf("rate_limit_exceeded", "rate_limit_error", "resource_exhausted", "429") } ->
                error(CloudErrorType.RATE_LIMITED, true, "Rate limited — retry after a short delay")
            values.any { it in setOf("invalid_api_key", "authentication_error", "unauthenticated", "permission_denied", "unauthorized", "401", "403") } ->
                error(CloudErrorType.UNAUTHORIZED, false, "Authentication or permission failed — check credentials")
            values.any { it in setOf("model_not_found", "not_found", "404") } ->
                error(CloudErrorType.MODEL_NOT_FOUND, false, "Model or provider endpoint was not found")
            values.any { it in setOf("context_length_exceeded", "maximum_context_length_exceeded", "context_length", "413") } ->
                error(CloudErrorType.CONTEXT_LENGTH, false, "Prompt exceeds model context window")
            values.any { it in setOf("content_filter", "content_policy_violation", "safety", "prohibited_content") } ->
                error(CloudErrorType.CONTENT_FILTERED, false, "Content policy violation")
            values.any { it in setOf("timeout", "deadline_exceeded", "504") } ->
                error(CloudErrorType.TIMEOUT, true, "Provider request timed out")
            values.any { it in setOf("unavailable", "overloaded", "internal", "server_error", "500", "503") } ->
                error(CloudErrorType.TEMPORARY_OVERLOAD, true, "Provider is temporarily unavailable")
            values.any { it in setOf("network_interruption", "connection_lost", "transport_error") } ->
                error(CloudErrorType.NETWORK_INTERRUPTION, true, "Network connection was interrupted")
            values.any { it in setOf("invalid_argument", "invalid_request_error", "invalid_request", "400") } ->
                error(CloudErrorType.INVALID_REQUEST, false, "Provider rejected the request")
            else -> error(CloudErrorType.UNKNOWN, false, "Provider returned an unrecognized error")
        }
        return mapped.copy(
            retryAfterMs = if (mapped.retryable) parseRetryAfterMs(retryAfterHeader, nowMs) else null,
            provider = provider,
            safeCode = mapped.type.name,
        )
    }

    private fun classify429(body: String): MappedError = when {
        body.containsAny("spend limit", "billing hard limit", "budget exceeded") ->
            error(CloudErrorType.SPEND_LIMIT, false, "Provider spending limit reached")
        body.containsAny("credit", "insufficient_quota", "billing", "exceeded your current quota", "quota") ->
            error(CloudErrorType.QUOTA_EXCEEDED, false, "Cloud quota exhausted — upgrade billing or wait for reset")
        else -> error(CloudErrorType.RATE_LIMITED, true, "Rate limited (429) — retry with backoff")
    }

    private fun error(type: CloudErrorType, retryable: Boolean, message: String) =
        MappedError(type = type, retryable = retryable, message = message)

    private fun parseRetryAfterMs(value: String?, nowMs: Long): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { seconds -> return seconds.coerceAtLeast(0L) * 1_000L }
        return try {
            val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (target - nowMs).coerceAtLeast(0L)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val String.hasContextErr: Boolean
        get() = containsAny("context_length_exceeded", "maximum context length", "too many tokens", "tokens exceed", "context window")

    private val String.hasSafetyErr: Boolean
        get() = containsAny("content_filter", "content_policy_violation", "safety", "harmful", "HARM_CATEGORY", "flagged", "violates our usage", "content management policy")

    private val String.hasToolSchemaErr: Boolean
        get() = containsAny("invalid_function_parameters", "function parameters", "function declaration", "function_declaration", "tool schema", "tool_schema", "input_schema", "invalid schema", "schema.type", "schema type", "unknown enum value")

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }
}
