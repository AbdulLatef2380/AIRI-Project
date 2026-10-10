package com.airi.assistant.connector

/** Persists only which runtime connectors the user explicitly connected; never stores credentials. */
interface ConnectorConnectionStateStore {
    fun connectedIds(): Set<String>
    fun setConnected(connectorId: String, connected: Boolean): Boolean
}

/** Isolated default for tests and callers that do not have persistent storage. */
class InMemoryConnectorConnectionStateStore : ConnectorConnectionStateStore {
    private val ids = linkedSetOf<String>()

    @Synchronized
    override fun connectedIds(): Set<String> = ids.toSet()

    @Synchronized
    override fun setConnected(connectorId: String, connected: Boolean): Boolean {
        require(connectorId.isNotBlank()) { "connectorId must not be blank" }
        if (connected) ids.add(connectorId) else ids.remove(connectorId)
        return true
    }
}
