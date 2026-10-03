package com.airi.assistant.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryConnectorContractTest {
    @Test
    fun exactly_seven_catalog_surfaces_are_in_the_primary_gate() {
        assertEquals(7, PrimaryConnectorContracts.all.size)
        PrimaryConnectorContracts.all.forEach { contract ->
            val definition = OfficialConnectorCatalog.get(contract.catalogId)
            assertNotNull("missing catalog definition: ${contract.catalogId}", definition)
            assertEquals(contract.runtimeId, definition!!.runtimeConnectorId())
            assertEquals(contract.authMode, ConnectorAuthStrategies.forMeta(definition.toConnectorMeta()).mode)
            assertTrue(contract.requiresHealthyRuntime)
        }
    }

    @Test
    fun primary_surfaces_have_real_partial_definitions_not_coming_soon() {
        assertTrue(PrimaryConnectorContracts.all.all {
            OfficialConnectorCatalog.get(it.catalogId)?.status == ConnectorAvailability.PARTIAL
        })
    }

    @Test
    fun shared_google_runtime_is_used_by_all_three_google_surfaces() {
        assertEquals(setOf("google"), PrimaryConnectorContracts.all
            .filter { it.catalogId.startsWith("google_") }
            .map { it.runtimeId }.toSet())
    }
}
