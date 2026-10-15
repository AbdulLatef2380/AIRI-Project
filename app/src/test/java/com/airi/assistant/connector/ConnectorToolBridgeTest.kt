package com.airi.assistant.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorToolBridgeTest {
    @Test
    fun exposes_only_granted_actions_for_healthy_connectors() {
        val registry = ConnectorRegistry()
        registry.register(TestConnector("github", connected = true, healthy = true))
        registry.register(TestConnector("offline", connected = false, healthy = false))
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            set("github", ConnectorAccessProfile.READ_ONLY)
            set("offline", ConnectorAccessProfile.READ_ONLY)
        }
        val bridge = bridge(registry, profiles)
        val names = bridge.asToolSchemas().map { it.name }
        assertTrue(names.contains("connector_github_list_repos"))
        assertFalse(names.contains("connector_github_create_issue"))
        assertFalse(names.any { it.contains("offline") })
    }

    @Test
    fun invoke_uses_runtime_manager_and_returns_connector_output() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = TestConnector("github", connected = true, healthy = true)
        registry.register(connector)
        val profiles = InMemoryConnectorAccessProfileStore().apply { set("github", ConnectorAccessProfile.READ_ONLY) }
        val result = bridge(registry, profiles).invoke("connector_github_list_repos", emptyMap())
        assertEquals(ConnectorOutput.Success("github:list_repos"), result)
        assertEquals("list_repos", connector.lastAction)
    }

    @Test
    fun known_tool_remains_resolvable_after_disconnect_and_returns_stable_failure() = runBlocking {
        val registry = ConnectorRegistry()
        registry.register(TestConnector("github", connected = true, healthy = true))
        val profiles = InMemoryConnectorAccessProfileStore().apply { set("github", ConnectorAccessProfile.READ_ONLY) }
        val bridge = bridge(registry, profiles)
        registry.disconnect("github")
        assertFalse(bridge.asToolSchemas().any { it.name == "connector_github_list_repos" })
        assertTrue(bridge.handles("connector_github_list_repos"))
        val result = bridge.invoke("connector_github_list_repos", emptyMap())
        assertEquals("not_connected", (result as ConnectorOutput.Failure).code)
    }

    @Test
    fun include_unavailable_exposes_granted_action_for_truthful_failure() {
        val registry = ConnectorRegistry()
        registry.register(TestConnector("google", connected = false, healthy = false))
        val profiles = InMemoryConnectorAccessProfileStore().apply { set("google", ConnectorAccessProfile.READ_ONLY) }
        val names = bridge(registry, profiles).asToolSchemas(includeUnavailable = true).map { it.name }
        assertTrue(names.contains("connector_google_list_repos"))
        // Inventory mode reports registered write actions too; the runtime still
        // rejects them unless the typed approval path is satisfied.
        assertTrue(names.contains("connector_google_create_issue"))
    }

    @Test
    fun no_profile_exposes_no_actions_and_invocation_fails_closed() = runBlocking {
        val registry = ConnectorRegistry()
        registry.register(TestConnector("github", connected = true, healthy = true))
        val profiles = InMemoryConnectorAccessProfileStore()
        val runtime = ConnectorRuntimeManager(registry, profiles)
        val bridge = ConnectorToolBridge(registry, runtime, profiles)
        assertTrue(bridge.asToolSchemas().isEmpty())
        assertTrue(bridge.handles("connector_github_list_repos"))
        val direct = runtime.execute("github", ConnectorInput(action = "list_repos")) as ConnectorOutput.Failure
        assertEquals("permission_denied", direct.code)
        val result = bridge.invoke("connector_github_list_repos", emptyMap()) as ConnectorOutput.Failure
        assertEquals("permission_denied", result.code)
    }

    @Test
    fun inventory_includes_registered_actions_without_grant_but_execution_stays_closed() = runBlocking {
        val registry = ConnectorRegistry()
        registry.register(TestConnector("github", connected = true, healthy = true))
        val profiles = InMemoryConnectorAccessProfileStore()
        val bridge = bridge(registry, profiles)
        assertTrue(bridge.asToolSchemas(includeUnavailable = true).any {
            it.name == "connector_github_list_repos"
        })
        assertTrue(bridge.invoke("connector_github_list_repos", emptyMap()) is ConnectorOutput.Failure)
    }

    @Test
    fun full_access_profile_does_not_bypass_action_confirmation() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = TestConnector("github", connected = true, healthy = true)
        registry.register(connector)
        val profiles = InMemoryConnectorAccessProfileStore().apply { set("github", ConnectorAccessProfile.FULL_ACCESS) }
        val bridge = bridge(registry, profiles)
        assertFalse(bridge.asToolSchemas().any { it.name == "connector_github_create_issue" })
        val result = bridge.invoke("connector_github_create_issue", emptyMap()) as ConnectorOutput.Failure
        assertEquals("approval_required", result.code)
        assertNull(connector.lastAction)
    }

    @Test
    fun grants_are_scoped_to_the_declared_catalog_surface() {
        val registry = ConnectorRegistry()
        registry.register(TestConnector("microsoft_graph", connected = true, healthy = true))
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            set("microsoft_outlook", ConnectorAccessProfile.READ_ONLY)
        }
        val bridge = bridge(registry, profiles)
        val names = bridge.asToolSchemas().map { it.name }.toSet()
        assertTrue("connector_microsoft_graph_outlook_mail_read" in names)
        assertFalse("connector_microsoft_graph_teams_list_joined" in names)
        assertFalse("connector_microsoft_graph_sharepoint_site_read" in names)
        assertEquals("microsoft_graph", bridge.connectorIdForTool("connector_microsoft_graph_outlook_mail_read"))
        assertEquals("outlook_mail_read", bridge.actionForTool("connector_microsoft_graph_outlook_mail_read")?.id)
    }

    @Test
    fun shared_connector_does_not_expose_an_unhealthy_action_surface() {
        val registry = ConnectorRegistry()
        registry.register(SurfaceScopedConnector())
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            set("mail_surface", ConnectorAccessProfile.READ_ONLY)
            set("calendar_surface", ConnectorAccessProfile.READ_ONLY)
        }

        val names = ConnectorToolBridge(registry, ConnectorRuntimeManager(registry, profiles), profiles)
            .asToolSchemas()
            .map { it.name }

        assertTrue(names.contains("connector_shared_mail_read"))
        assertFalse(names.contains("connector_shared_calendar_read"))
    }

    private fun bridge(registry: ConnectorRegistry, profiles: ConnectorAccessProfileStore) =
        ConnectorToolBridge(registry, ConnectorRuntimeManager(registry, profiles), profiles)

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
        override fun agentActions(): List<ConnectorAgentAction> = if (id == "microsoft_graph") {
            listOf(
                ConnectorAgentAction("outlook_mail_read", "Read Outlook", surfaceId = "microsoft_outlook"),
                ConnectorAgentAction("teams_list_joined", "List Teams", surfaceId = "microsoft_teams"),
                ConnectorAgentAction("sharepoint_site_read", "Read SharePoint", surfaceId = "microsoft_sharepoint"),
            )
        } else listOf(
            ConnectorAgentAction("list_repos", "List repositories"),
            ConnectorAgentAction("create_issue", "Create issue", ConnectorPermissionLevel.WRITE),
        )
        override suspend fun execute(input: ConnectorInput): ConnectorOutput {
            lastAction = input.action
            return ConnectorOutput.Success("$id:${input.action}")
        }
    }

    private class SurfaceScopedConnector : Connector {
        override val id = "shared"
        override val name = "shared"
        override val description = "shared provider"
        override val type = ConnectorType.APP
        private val stateFlow = MutableStateFlow(ConnectorState(true, true))
        override fun meta() = ConnectorMeta(id, name, description, type)
        override fun state() = stateFlow
        override fun stateForAction(action: ConnectorAgentAction): ConnectorState =
            if (action.surfaceId == "calendar_surface") ConnectorState(true, false, "Authorization required") else stateFlow.value
        override suspend fun connect() = stateFlow.value
        override suspend fun disconnect() { stateFlow.value = ConnectorState(false, false) }
        override fun agentActions() = listOf(
            ConnectorAgentAction("mail_read", "Read mail", surfaceId = "mail_surface"),
            ConnectorAgentAction("calendar_read", "Read calendar", surfaceId = "calendar_surface"),
        )
        override suspend fun execute(input: ConnectorInput) = ConnectorOutput.Success(input.action)
    }
}
