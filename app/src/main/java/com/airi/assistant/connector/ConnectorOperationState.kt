package com.airi.assistant.connector

/** User-visible lifecycle of one connector operation. */
sealed class ConnectorOperationState {
    data object Idle : ConnectorOperationState()
    data class Running(val operationId: Long, val connectorId: String, val action: String) : ConnectorOperationState()
    data class Success(val operationId: Long) : ConnectorOperationState()
    data class AwaitingApproval(val operationId: Long, val approvalId: String) : ConnectorOperationState()
    data class Failed(val operationId: Long, val code: String, val message: String, val retryable: Boolean) : ConnectorOperationState()
    data class Cancelled(val operationId: Long) : ConnectorOperationState()
    data class TimedOut(val operationId: Long, val timeoutMs: Long) : ConnectorOperationState()
}
