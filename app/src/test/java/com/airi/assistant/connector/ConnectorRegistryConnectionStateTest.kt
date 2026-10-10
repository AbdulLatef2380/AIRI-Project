package com.airi.assistant.connector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRegistryConnectionStateTest {
    @Test
    fun restoresOnlyPersistedRegisteredConnectorsAndDisconnectClearsMarker() = runBlocking {
        val stateStore = InMemoryConnectorConnectionStateStore().apply {
            setConnected("saved", true)
            setConnected("not_registered", true)
        }
        val connector = RestoreConnector("saved")
        val registry = ConnectorRegistry(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectionStateStore = stateStore,
        )
        registry.register(connector)

        registry.restorePersistedConnections()
        assertEquals(1, connector.connectCalls)
        assertEquals(setOf("saved", "not_registered"), stateStore.connectedIds())

        assertTrue(registry.disconnect("saved"))
        assertEquals(setOf("not_registered"), stateStore.connectedIds())
    }

    @Test
    fun ordinaryRuntimeConnectDoesNotCreatePersistentConnectionMarker() = runBlocking {
        val stateStore = InMemoryConnectorConnectionStateStore()
        val connector = RestoreConnector("runtime_only")
        val registry = ConnectorRegistry(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectionStateStore = stateStore,
        )
        registry.register(connector)

        registry.connect("runtime_only")
        assertTrue(stateStore.connectedIds().isEmpty())

        registry.connect("runtime_only", persistConnection = true)
        assertEquals(setOf("runtime_only"), stateStore.connectedIds())
    }

    private class RestoreConnector(override val id: String) : Connector {
        override val name = id
        override val description = id
        override val type = ConnectorType.APP
        private val _state = MutableStateFlow(ConnectorState(connected = false))
        var connectCalls = 0
            private set
        override fun meta() = ConnectorMeta(id, name, description, type)
        override fun state() = _state
        override suspend fun connect(): ConnectorState {
            connectCalls += 1
            return ConnectorState(connected = true, healthy = true, statusLine = "connected")
                .also { _state.value = it }
        }
        override suspend fun disconnect() { _state.value = ConnectorState(connected = false) }
        override suspend fun execute(input: ConnectorInput) = ConnectorOutput.Failure("unused", "unused")
    }
}
