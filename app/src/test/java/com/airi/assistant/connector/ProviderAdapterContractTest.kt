package com.airi.assistant.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAdapterContractTest {
    @Test
    fun every_remaining_catalog_definition_has_one_declarative_adapter_contract() {
        val remaining = OfficialConnectorCatalog.all.filter {
            it.id in RemainingProviderAdapterContracts.all.map { contract -> contract.catalogId }
        }
        assertEquals(remaining.size, RemainingProviderAdapterContracts.all.size)
        assertEquals(remaining.size, RemainingProviderAdapterContracts.all.map { it.catalogId }.toSet().size)
        remaining.forEach { definition ->
            val contract = RemainingProviderAdapterContracts.get(definition.id)
            assertNotNull("missing adapter contract: ${definition.id}", contract)
            assertTrue(when (contract!!.authMode) {
                ConnectorAuthMode.OAUTH2_PKCE -> definition.authenticationType == ConnectorAuthenticationType.OAUTH2 || definition.authenticationType == ConnectorAuthenticationType.OAUTH2_AND_API
                ConnectorAuthMode.API_KEY -> definition.authenticationType == ConnectorAuthenticationType.API_KEY
                ConnectorAuthMode.PERSONAL_ACCESS_TOKEN -> definition.authenticationType == ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN
                else -> true
            })
            assertTrue(contract.requiredScopes.isNotEmpty())
            assertTrue(contract.healthEndpoint.startsWith("https://"))
            assertTrue(contract.adapterId.isNotBlank())
            assertTrue(contract.officialDocsUrl.startsWith("https://"))
            assertFalse(contract.isExecutable)
            if (definition.id in setOf("microsoft_onedrive", "microsoft_teams")) {
                assertEquals(ConnectorAvailability.PARTIAL, definition.status)
            } else {
                assertEquals(ConnectorAvailability.COMING_SOON, definition.status)
            }
        }
    }

    @Test
    fun no_contract_can_be_mistaken_for_a_live_adapter() {
        assertTrue(RemainingProviderAdapterContracts.all.all { !it.isExecutable && it.readOnlyFirst })
    }
}
