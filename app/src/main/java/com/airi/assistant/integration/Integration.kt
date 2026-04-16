package com.airi.assistant.integration

data class IntegrationState(
    val id: String,
    val name: String,
    val description: String,
    val isConnected: Boolean,
    val lastUpdated: Long
)

interface Integration {
    val id: String
    val name: String
    val description: String
    fun connect()
    fun disconnect()
    fun state(): IntegrationState
}
