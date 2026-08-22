package com.airi.assistant.connector

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRuntimeManagerTest {

    @Test
    fun executesOnlyAfterHealthCheckReconnectsConnector() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = FakeConnector(id = "notion", initiallyHealthy = false)
        registry.register(connector)
        val runtime = ConnectorRuntimeManager(registry)

        val result = runtime.execute("notion", ConnectorInput(action = "read"))

        assertTrue(result is ConnectorOutput.Success)
        assertEquals(1, connector.connectCalls)
        assertEquals(1, connector.executeCalls)
    }

    @Test
    fun broadcastAwaitsEveryConnectorResult() = runBlocking {
        val registry = ConnectorRegistry()
        registry.register(FakeConnector(id = "fast", initiallyHealthy = true, executionDelayMs = 0))
        registry.register(FakeConnector(id = "slow", initiallyHealthy = true, executionDelayMs = 650))
        val runtime = ConnectorRuntimeManager(registry)

        val results = runtime.broadcast(ConnectorType.MCP, ConnectorInput(action = "read"))

        assertEquals(setOf("fast", "slow"), results.keys)
        assertTrue(results.values.all { it is ConnectorOutput.Success })
    }

    private class FakeConnector(
        override val id: String,
        initiallyHealthy: Boolean,
        private val executionDelayMs: Long = 0L
    ) : Connector {
        override val name: String = id
        override val description: String = "Test connector"
        override val type: ConnectorType = ConnectorType.MCP
        private val stateFlow = MutableStateFlow(
            ConnectorState(connected = initiallyHealthy, healthy = initiallyHealthy)
        )
        var connectCalls = 0
        var executeCalls = 0

        override fun meta(): ConnectorMeta = ConnectorMeta(id, name, description, type)
        override fun state() = stateFlow
        override suspend fun connect(): ConnectorState {
            connectCalls++
            stateFlow.value = ConnectorState(connected = true, healthy = true)
            return stateFlow.value
        }
        override suspend fun disconnect() {
            stateFlow.value = ConnectorState(connected = false, healthy = false)
        }
        override suspend fun execute(input: ConnectorInput): ConnectorOutput {
            executeCalls++
            if (executionDelayMs > 0) delay(executionDelayMs)
            return ConnectorOutput.Success("${id}:${input.action}")
        }
    }
}
