package com.airi.assistant.connector

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorCatalogTest {
    @Test
    fun catalogHasThirtyFiveUniqueEntriesAndNoComingSoonEntryIsEnabled() {
        val entries = OfficialConnectorCatalog.all
        assertEquals(35, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.filter { it.status == ConnectorAvailability.COMING_SOON }.all { !it.enabled })
        assertTrue(entries.all { it.author == "AIRI" })
    }

    @Test
    fun searchCoversProviderCategoryTagsAndCapabilities() {
        assertTrue(OfficialConnectorCatalog.search("email").any { it.id == "google_gmail" })
        assertTrue(OfficialConnectorCatalog.search("Microsoft").any { it.id == "microsoft_teams" })
        assertTrue(OfficialConnectorCatalog.search("issues.create").any { it.id == "github" })
        assertTrue(OfficialConnectorCatalog.search("messaging").any { it.id == "telegram" })
    }

    @Test
    fun writeCapabilitiesRequireConfirmation() {
        val github = OfficialConnectorCatalog.get("github")!!
        val createIssue = github.capabilities.first { it.id == "issues.create" }
        assertEquals(ConnectorPermissionLevel.WRITE, createIssue.permission)
        assertTrue(createIssue.requiresConfirmation)
        assertFalse(github.capabilities.first { it.id == "issues.read" }.requiresConfirmation)
    }

    @Test
    fun registryExposesCatalogSearchWithoutMakingComingSoonExecutable() = runBlocking {
        val registry = ConnectorRegistry(this)
        val results = registry.catalogSearch("Outlook")
        assertTrue(results.any { it.id == "microsoft_outlook" })
        assertEquals(ConnectorAvailability.COMING_SOON, results.first { it.id == "microsoft_outlook" }.availability)
        assertFalse(registry.get("microsoft_outlook") != null)
    }

    private class FakeConnector(
        override val id: String,
        override val name: String,
        override val type: ConnectorType = ConnectorType.APP,
    ) : Connector {
        private val state = MutableStateFlow(ConnectorState(false))
        override val description: String = name
        override fun meta() = ConnectorMeta(id, name, description, type, tags = listOf("test"))
        override fun state() = state
        override suspend fun connect() = state.value
        override suspend fun disconnect() = Unit
        override suspend fun execute(input: ConnectorInput) = ConnectorOutput.Failure("unsupported", "test")
    }
}
