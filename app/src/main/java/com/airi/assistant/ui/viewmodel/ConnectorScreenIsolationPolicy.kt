package com.airi.assistant.ui.viewmodel

/**
 * Guards asynchronous connector-screen updates against stale provider results.
 * A result may update the UI only while its connector remains the active target.
 */
object ConnectorScreenIsolationPolicy {
    fun acceptsResult(expectedConnectorId: String, activeConnectorId: String?): Boolean =
        expectedConnectorId.isNotBlank() && expectedConnectorId == activeConnectorId

    fun acceptsDialogUpdate(expectedConnectorId: String, activeConnectorId: String?): Boolean =
        acceptsResult(expectedConnectorId, activeConnectorId)
}
