package com.airi.assistant.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorToolBridgeTest {

    @Test
    fun exposesOnlyConnectedHealthyReadActions() {
        val registry = ConnectorRegistry()
        registry.register(TestConnector("github", connected = true, healthy = true))
        registry.register(TestConnector("offline", connected = false, healthy = false))
        val bridge = ConnectorToolBridge(registry, ConnectorRuntimeManager(registry))

        val names = bridge.asToolSchemas().map { it.name }
        assertTrue(names.contains("connector_github_list_repos"))
        assertFalse(names.contains("connector_github_create_issue"))
        assertFalse(names.any { it.contains("offline") })
    }

    @Test
    fun invokeUsesConnectorRuntimeManagerAndReturnsConnectorOutput() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = TestConnector("github", connected = true, healthy = true)
        registry.register(connector)
        val bridge = ConnectorToolBridge(registry, ConnectorRuntimeManager(registry))

        val result = bridge.invoke(
            "connector_github_list_repos",
            emptyMap()
        )

        assertEquals(ConnectorOutput.Success("github:list_repos"), result)
        assertEquals("list_repos", connector.lastAction)
    }

    private class TestConnector(
        override val id: String,
        private val connected: Boolean,
        private val healthy: Boolean,
    ) : Connector {
        override val name = id
        override val description = "test"
        override val type = ConnectorType.APP
        private val stateFlow = MutableStateFlow(ConnectorState(connected, healthy))
        var lastAction: String? = null

        override fun meta() = ConnectorMeta(id, name, description, type)
        override fun state() = stateFlow
        override suspend fun connect() = stateFlow.value
        override suspend fun disconnect() { stateFlow.value = ConnectorState(false, false) }
        override fun agentActions() = listOf(
            ConnectorAgentAction("list_repos", "List repositories"),
            ConnectorAgentAction("create_issue", "Create issue", ConnectorPermissionLevel.WRITE),
        )
        override suspend fun execute(input: ConnectorInput): ConnectorOutput {
            lastAction = input.action
            return ConnectorOutput.Success("$id:${input.action}")
        }
    }
}
