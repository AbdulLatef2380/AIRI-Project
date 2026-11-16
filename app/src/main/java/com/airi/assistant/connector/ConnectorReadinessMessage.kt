package com.airi.assistant.connector

/**
 * User/model-safe connector diagnostics. Provider response bodies and credentials
 * are intentionally never included; callers supply only the already-sanitized
 * failure code and message.
 */
object ConnectorReadinessMessage {
    fun failure(connectorName: String, code: String, detail: String): String {
        val name = connectorName.trim().ifBlank { "Connector" }
        val (reason, action) = when (code.lowercase()) {
            "not_connected", "disconnected" ->
                "the connector is registered but not connected" to "Connect $name"
            "authorization_required", "oauth_required" ->
                "OAuth authorization is incomplete or expired" to "Connect $name and grant the requested access"
            "permission_denied", "provider_permission_denied" ->
                "the required action or provider permission is not granted" to "Review the connector access profile"
            "approval_required" ->
                "this operation requires an explicit user approval" to "Approve the operation in the execution center"
            "configuration_required" ->
                "provider configuration is incomplete" to "Open $name setup"
            "provider_error", "api_error", "network_error", "unhealthy" ->
                "the connector is connected but the health check or provider request failed" to "Retry the health check"
            else -> detail.trim().ifBlank { "the connector could not execute this action" } to "Review $name setup"
        }
        return "$name is registered, but not ready: Reason: $reason. Next action: $action. Details: ${detail.trim().take(240)}"
    }
}
