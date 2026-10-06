package com.airi.assistant.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorAuthStrategyTest {
    @Test
    fun every_catalog_entry_resolves_to_a_declared_strategy() {
        val strategies = OfficialConnectorCatalog.all.map { definition ->
            ConnectorAuthStrategies.forMeta(definition.toConnectorMeta())
        }
        assertEquals(OfficialConnectorCatalog.all.size, strategies.size)
        assertTrue(strategies.all { it.connectorId.isNotBlank() && it.provider.isNotBlank() })
        assertTrue(strategies.all { it.mode != ConnectorAuthMode.NONE })
    }

    @Test
    fun coming_soon_entries_cannot_start_authentication() {
        OfficialConnectorCatalog.all
            .filter { it.status == ConnectorAvailability.COMING_SOON }
            .forEach { definition ->
                val strategy = ConnectorAuthStrategies.forMeta(definition.toConnectorMeta())
                assertEquals(ConnectorAuthMode.COMING_SOON, strategy.mode)
                assertFalse(strategy.isExecutable)
                assertFalse(strategy.officialAuthorizationRequired)
            }
    }

    @Test
    fun implemented_provider_flows_are_resolved_without_ui_id_lists() {
        val expected = mapOf(
            "google_gmail" to ConnectorAuthMode.OAUTH2_PKCE,
            "google_docs" to ConnectorAuthMode.OAUTH2_PKCE,
            "google_sheets" to ConnectorAuthMode.OAUTH2_PKCE,
            "google_contacts" to ConnectorAuthMode.OAUTH2_PKCE,
            "google_tasks" to ConnectorAuthMode.OAUTH2_PKCE,
            "github" to ConnectorAuthMode.PERSONAL_ACCESS_TOKEN,
            "telegram" to ConnectorAuthMode.API_KEY,
            "notion" to ConnectorAuthMode.MCP_CONFIGURATION,
            "microsoft_outlook" to ConnectorAuthMode.OAUTH2_PKCE,
            "microsoft_calendar" to ConnectorAuthMode.OAUTH2_PKCE,
            "microsoft_onedrive" to ConnectorAuthMode.OAUTH2_PKCE,
            "microsoft_teams" to ConnectorAuthMode.OAUTH2_PKCE,
            "microsoft_sharepoint" to ConnectorAuthMode.OAUTH2_PKCE,
            "microsoft_todo" to ConnectorAuthMode.OAUTH2_PKCE,
            "zapier" to ConnectorAuthMode.OAUTH2_PKCE,
            "gitlab" to ConnectorAuthMode.PERSONAL_ACCESS_TOKEN,
            "linear" to ConnectorAuthMode.API_KEY,
            "slack" to ConnectorAuthMode.API_KEY,
            "discord" to ConnectorAuthMode.API_KEY,
            "asana" to ConnectorAuthMode.PERSONAL_ACCESS_TOKEN,
            "todoist" to ConnectorAuthMode.API_KEY,
            "figma" to ConnectorAuthMode.PERSONAL_ACCESS_TOKEN,
        )
        expected.forEach { (id, mode) ->
            val strategy = ConnectorAuthStrategies.forMeta(
                OfficialConnectorCatalog.get(id)!!.toConnectorMeta()
            )
            assertEquals(id, strategy.connectorId)
            assertEquals(mode, strategy.mode)
            assertTrue(strategy.officialAuthorizationRequired)
        }
    }
}
