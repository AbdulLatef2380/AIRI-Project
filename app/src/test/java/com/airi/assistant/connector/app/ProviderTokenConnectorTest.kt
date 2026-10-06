package com.airi.assistant.connector.app

import com.airi.assistant.connector.ConnectorAuthenticationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTokenConnectorTest {
    @Test
    fun seven_catalog_surfaces_have_explicit_provider_configs() {
        val configs = ProviderTokenConnector.configs()
        assertEquals(7, configs.size)
        assertEquals(configs.size, configs.map { it.id }.toSet().size)
        assertTrue(configs.all { it.healthPath.startsWith("https://") })
        assertTrue(configs.all { it.documentation.startsWith("https://") })
        assertTrue(configs.all { it.readPath == null || it.readPath.startsWith("https://") })
        assertTrue(configs.none { it.id in setOf("jira", "trello", "clickup", "monday", "bitbucket") })
    }

    @Test
    fun provider_auth_modes_match_credential_contracts() {
        val byId = ProviderTokenConnector.configs().associateBy { it.id }
        assertEquals(ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, byId.getValue("gitlab").authType)
        assertEquals(ConnectorAuthenticationType.API_KEY, byId.getValue("linear").authType)
        assertEquals(ConnectorAuthenticationType.API_KEY, byId.getValue("slack").authType)
        assertEquals(ConnectorAuthenticationType.API_KEY, byId.getValue("discord").authType)
        assertEquals(ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, byId.getValue("asana").authType)
        assertEquals(ConnectorAuthenticationType.API_KEY, byId.getValue("todoist").authType)
        assertEquals(ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, byId.getValue("figma").authType)
        assertTrue(byId.values.all { it.credentialLabel.isNotBlank() })
        assertFalse(byId.values.any { it.readPath?.contains("/write") == true })
    }
}
