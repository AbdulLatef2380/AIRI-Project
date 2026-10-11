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
    fun firstConnectorExperienceBatch_hasTwentyCanonicalDetailSurfaces() {
        val expected = listOf(
            "google_gmail", "google_calendar", "google_drive", "google_docs", "google_sheets",
            "google_contacts", "google_tasks", "google_meet", "microsoft_outlook", "microsoft_calendar",
            "microsoft_onedrive", "microsoft_teams", "microsoft_sharepoint", "microsoft_todo", "github",
            "gitlab", "figma", "jira", "linear", "slack",
        )

        assertEquals(expected, OfficialConnectorCatalog.all.take(expected.size).map { it.id })
        expected.forEach { id ->
            val definition = OfficialConnectorCatalog.get(id)
            requireNotNull(definition)
            assertEquals(id, definition.toConnectorMeta().catalogId)
            assertTrue(definition.name.isNotBlank())
            assertTrue(definition.description.isNotBlank())
            assertTrue(definition.website?.startsWith("https://") == true)
        }
    }

    @Test
    fun everyCatalogConnector_usesTheSharedDetailMetadataAndAuthContract() {
        OfficialConnectorCatalog.all.forEach { definition ->
            val meta = definition.toConnectorMeta()
            val strategy = ConnectorAuthStrategies.forMeta(meta)

            assertEquals(definition.id, meta.catalogId)
            assertEquals(definition.id, strategy.connectorId)
            assertTrue(meta.name.isNotBlank())
            assertTrue(meta.description.isNotBlank())
            assertTrue(strategy.provider.isNotBlank())
            assertTrue(strategy.isExecutable == (definition.status != ConnectorAvailability.COMING_SOON))
            definition.capabilities.forEach { capability ->
                assertTrue(capability.id.isNotBlank())
                assertTrue(capability.description.isNotBlank())
                if (capability.permission != ConnectorPermissionLevel.READ) {
                    assertTrue(capability.requiresConfirmation)
                }
            }
        }
    }

    @Test
    fun registryCatalogSurface_containsEveryCanonicalConnectorId() {
        val catalogIds = OfficialConnectorCatalog.all.map { it.id }
        val surfaceIds = ConnectorRegistry().catalogMeta().map { it.id }

        assertEquals(catalogIds, surfaceIds.take(catalogIds.size))
        assertTrue(surfaceIds.containsAll(catalogIds))
    }

    @Test
    fun bootstrappedConnectorSurface_coversAllFortyThreeConnectors() {
        val liveOnlyIds = listOf(
            "remote_llm", "android_intent", "voice_mtmd", "clipboard", "device_apps",
            "contacts", "system_info", "ifttt", "n8n",
        )
        val registry = ConnectorRegistry().also { target ->
            liveOnlyIds.forEach { id -> target.register(FakeConnector(id, id)) }
        }
        val surface = registry.catalogMeta()

        assertEquals(43, surface.size)
        assertEquals(OfficialConnectorCatalog.all.map { it.id }, surface.take(OfficialConnectorCatalog.all.size).map { it.id })
        assertEquals(liveOnlyIds.toSet(), surface.drop(OfficialConnectorCatalog.all.size).map { it.id }.toSet())
        surface.forEach { meta ->
            assertTrue(meta.id.isNotBlank())
            assertTrue(meta.name.isNotBlank())
            assertTrue(meta.description.isNotBlank())
        }
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
