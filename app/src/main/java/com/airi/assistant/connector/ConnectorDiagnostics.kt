package com.airi.assistant.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stable, non-sensitive error classes exposed to runtime diagnostics and UI. */
enum class ConnectorErrorClass {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMIT,
    NETWORK,
    TIMEOUT,
    INVALID_REQUEST,
    NOT_FOUND,
    PROVIDER,
    INTERNAL,
}

data class ConnectorDiagnosticSnapshot(
    val connectorId: String,
    val lastSuccessfulRequestMs: Long? = null,
    val lastFailureMs: Long? = null,
    val lastFailureClass: ConnectorErrorClass? = null,
    val lastFailureCode: String? = null,
    val lastRequestDurationMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val rateLimitObserved: Boolean = false,
)

/**
 * Bounded in-memory diagnostics. It intentionally stores codes and timings,
 * never credentials, request text, response bodies, or provider URLs.
 */
class ConnectorDiagnosticsStore(private val maxConnectors: Int = 128) {
    private val _snapshots = MutableStateFlow<Map<String, ConnectorDiagnosticSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ConnectorDiagnosticSnapshot>> = _snapshots.asStateFlow()

    @Synchronized
    fun record(connectorId: String, output: ConnectorOutput, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val current = _snapshots.value[connectorId] ?: ConnectorDiagnosticSnapshot(connectorId)
        val next = when (output) {
            is ConnectorOutput.Success, is ConnectorOutput.Streaming -> current.copy(
                lastSuccessfulRequestMs = nowMs,
                lastRequestDurationMs = durationMs.coerceAtLeast(0L),
                consecutiveFailures = 0,
            )
            is ConnectorOutput.ApprovalRequired -> current.copy(
                lastRequestDurationMs = durationMs.coerceAtLeast(0L),
            )
            is ConnectorOutput.Failure -> {
                val kind = ConnectorErrorClassifier.classify(output.code)
                current.copy(
                    lastFailureMs = nowMs,
                    lastFailureClass = kind,
                    lastFailureCode = output.code.take(64),
                    lastRequestDurationMs = durationMs.coerceAtLeast(0L),
                    consecutiveFailures = current.consecutiveFailures + 1,
                    rateLimitObserved = current.rateLimitObserved || kind == ConnectorErrorClass.RATE_LIMIT,
                )
            }
        }
        val updated = (_snapshots.value + (connectorId to next)).toList()
            .takeLast(maxConnectors.coerceAtLeast(1)).toMap()
        _snapshots.value = updated
    }

    @Synchronized
    fun clear(connectorId: String) {
        _snapshots.value = _snapshots.value - connectorId
    }
}

object ConnectorErrorClassifier {
    fun classify(code: String): ConnectorErrorClass = when (code.lowercase()) {
        "auth_required", "authorization_required", "not_connected", "invalid_credential" -> ConnectorErrorClass.AUTHENTICATION
        "permission_denied", "approval_required" -> ConnectorErrorClass.AUTHORIZATION
        "rate_limited", "http_429", "quota_rate_limited" -> ConnectorErrorClass.RATE_LIMIT
        "network_error", "connection_lost", "dns_error" -> ConnectorErrorClass.NETWORK
        "timeout" -> ConnectorErrorClass.TIMEOUT
        "invalid_params", "invalid_text", "invalid_binary", "invalid_request", "unknown_action", "undeclared_action" -> ConnectorErrorClass.INVALID_REQUEST
        "not_found" -> ConnectorErrorClass.NOT_FOUND
        "provider_error", "remote_error" -> ConnectorErrorClass.PROVIDER
        else -> ConnectorErrorClass.INTERNAL
    }
}
