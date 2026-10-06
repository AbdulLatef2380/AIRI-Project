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
        assertEquals(34, entries.size)
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
    fun catalogMetadata_keeps_shared_provider_surfaces_distinct() {
        val metas = ConnectorRegistry().catalogMeta()
        val gmail = metas.first { it.id == "google_gmail" }
        val calendar = metas.first { it.id == "google_calendar" }
        val teams = metas.first { it.id == "microsoft_teams" }

        assertEquals("google", gmail.runtimeId)
        assertEquals("google", calendar.runtimeId)
        assertEquals("Gmail", gmail.name)
        assertEquals("Google Calendar", calendar.name)
        assertEquals("microsoft_graph", teams.runtimeId)
        assertEquals("Microsoft Teams", teams.name)
        assertEquals("microsoft_teams", teams.catalogId)
    }

    @Test
    fun catalogDeclaresLeastPrivilegeOAuthScopesPerLiveSurface() {
        assertEquals(listOf(ConnectorProviderScopes.GOOGLE_GMAIL_READONLY), OfficialConnectorCatalog.get("google_gmail")!!.requiredScopes)
        assertEquals(listOf(ConnectorProviderScopes.GOOGLE_CALENDAR_EVENTS_OWNED_READONLY), OfficialConnectorCatalog.get("google_calendar")!!.requiredScopes)
        assertEquals(listOf(ConnectorProviderScopes.GOOGLE_DRIVE_METADATA_READONLY), OfficialConnectorCatalog.get("google_drive")!!.requiredScopes)
        assertEquals(listOf(ConnectorProviderScopes.GOOGLE_DOCS_READONLY), OfficialConnectorCatalog.get("google_docs")!!.requiredScopes)
        assertEquals(listOf(ConnectorProviderScopes.GOOGLE_SHEETS_READONLY), OfficialConnectorCatalog.get("google_sheets")!!.requiredScopes)
        assertEquals(listOf(ConnectorProviderScopes.GOOGLE_CONTACTS_READONLY), OfficialConnectorCatalog.get("google_contacts")!!.requiredScopes)
        assertEquals(listOf(ConnectorProviderScopes.GOOGLE_TASKS_READONLY), OfficialConnectorCatalog.get("google_tasks")!!.requiredScopes)
        assertEquals(
            listOf(ConnectorProviderScopes.MICROSOFT_USER_READ, ConnectorProviderScopes.MICROSOFT_MAIL_READ_BASIC),
            OfficialConnectorCatalog.get("microsoft_outlook")!!.requiredScopes,
        )
        assertTrue(OfficialConnectorCatalog.get("microsoft_calendar")!!.requiredScopes.contains(ConnectorProviderScopes.MICROSOFT_CALENDARS_READ_BASIC))
        assertTrue(OfficialConnectorCatalog.get("microsoft_onedrive")!!.requiredScopes.contains(ConnectorProviderScopes.MICROSOFT_FILES_READ))
        assertTrue(OfficialConnectorCatalog.get("microsoft_teams")!!.requiredScopes.contains(ConnectorProviderScopes.MICROSOFT_TEAM_READ_BASIC_ALL))
        assertTrue(OfficialConnectorCatalog.get("microsoft_todo")!!.requiredScopes.contains(ConnectorProviderScopes.MICROSOFT_TASKS_READ))
        assertEquals(listOf(ConnectorProviderScopes.ZAPIER_ZAP_READ), OfficialConnectorCatalog.get("zapier")!!.requiredScopes)
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
    fun registryExposesCatalogSearchWithoutMakingCatalogEntryExecutable() = runBlocking {
        val registry = ConnectorRegistry(this)
        val results = registry.catalogSearch("Outlook")
        assertTrue(results.any { it.id == "microsoft_outlook" })
        assertEquals(ConnectorAvailability.PARTIAL, results.first { it.id == "microsoft_outlook" }.availability)
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
