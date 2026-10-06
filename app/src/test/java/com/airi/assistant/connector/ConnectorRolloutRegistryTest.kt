package com.airi.assistant.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRolloutRegistryTest {
    @Test
    fun every_catalog_connector_belongs_to_one_rollout_batch() {
        val entries = ConnectorRolloutRegistry.all()
        assertEquals(OfficialConnectorCatalog.all.size, entries.size)
        assertEquals(entries.size, entries.map { it.connectorId }.toSet().size)
        assertTrue(entries.all { it.requiredAdapter.isNotBlank() })
        assertTrue(entries.all { it.authMode != ConnectorAuthMode.NONE })
    }

    @Test
    fun remaining_catalog_connectors_are_blocked_until_real_adapters_exist() {
        val missing = ConnectorRolloutRegistry.missingAdapters()
        assertFalse(missing.isEmpty())
        assertTrue(missing.all { !it.canStartAuthorization })
        assertTrue(missing.all { !it.blockedReason.isNullOrBlank() })
        assertTrue(missing.any { it.batch == ConnectorRolloutBatch.FILES })
        assertTrue(missing.any { it.batch == ConnectorRolloutBatch.PRODUCTIVITY })
        assertTrue(missing.any { it.batch == ConnectorRolloutBatch.DEVELOPMENT })
        assertTrue(missing.any { it.batch == ConnectorRolloutBatch.DESIGN_AND_MEETINGS })
    }

    @Test
    fun implemented_shared_adapters_are_not_duplicated_per_catalog_surface() {
        assertEquals("google", OfficialConnectorCatalog.get("google_gmail")!!.runtimeConnectorId())
        assertEquals("google", OfficialConnectorCatalog.get("google_calendar")!!.runtimeConnectorId())
        assertEquals("google", OfficialConnectorCatalog.get("google_docs")!!.runtimeConnectorId())
        assertEquals("google", OfficialConnectorCatalog.get("google_sheets")!!.runtimeConnectorId())
        assertEquals("google", OfficialConnectorCatalog.get("google_contacts")!!.runtimeConnectorId())
        assertEquals("google", OfficialConnectorCatalog.get("google_tasks")!!.runtimeConnectorId())
        assertEquals("microsoft_graph", OfficialConnectorCatalog.get("microsoft_outlook")!!.runtimeConnectorId())
        assertEquals("microsoft_graph", OfficialConnectorCatalog.get("microsoft_calendar")!!.runtimeConnectorId())
        assertEquals("microsoft_graph", OfficialConnectorCatalog.get("microsoft_onedrive")!!.runtimeConnectorId())
        assertEquals("microsoft_graph", OfficialConnectorCatalog.get("microsoft_teams")!!.runtimeConnectorId())
        assertEquals("microsoft_graph", OfficialConnectorCatalog.get("microsoft_todo")!!.runtimeConnectorId())
        assertEquals("notion_mcp", OfficialConnectorCatalog.get("notion")!!.runtimeConnectorId())
        assertEquals("LIVE_ADAPTERS", ConnectorRolloutRegistry.get("google_gmail")!!.batch.name)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("github")!!.readiness)
        assertEquals(ConnectorAdapterReadiness.CONFIGURATION_REQUIRED, ConnectorRolloutRegistry.get("zapier")!!.readiness)
        assertEquals("ServerSideZapierOAuthBroker", ConnectorRolloutRegistry.get("zapier")!!.requiredAdapter)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("microsoft_onedrive")!!.readiness)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("microsoft_teams")!!.readiness)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("microsoft_sharepoint")!!.readiness)
        assertEquals("microsoft_graph", OfficialConnectorCatalog.get("microsoft_sharepoint")!!.runtimeConnectorId())
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("google_docs")!!.readiness)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("google_sheets")!!.readiness)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("google_contacts")!!.readiness)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("google_tasks")!!.readiness)
        assertEquals(ConnectorAdapterReadiness.LIVE, ConnectorRolloutRegistry.get("microsoft_todo")!!.readiness)
    }

    @Test
    fun token_read_only_batch_is_live_and_authorizable() {
        val ids = listOf("gitlab", "linear", "slack", "discord", "asana", "todoist", "figma")
        ids.forEach { id ->
            val entry = ConnectorRolloutRegistry.get(id)!!
            assertEquals(ConnectorAdapterReadiness.LIVE, entry.readiness)
            assertTrue(entry.canStartAuthorization)
            assertEquals("ProviderTokenConnector", entry.requiredAdapter)
        }
    }
}
