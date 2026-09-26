package com.airi.assistant.connector

sealed class ConnectorReadinessState {
    data object NotInitialized : ConnectorReadinessState()
    data object Initializing : ConnectorReadinessState()
    data class Ready(val registeredCount: Int) : ConnectorReadinessState()
    data class Degraded(val registeredCount: Int, val reason: String) : ConnectorReadinessState()
    data class Failed(val reason: String) : ConnectorReadinessState()
}
