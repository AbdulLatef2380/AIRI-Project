package com.airi.assistant.connector

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorOAuthScopeResolverTest {
    @Test
    fun scopesAreUnionedOnlyFromActionsAllowedByExplicitSurfaceProfiles() {
        val gmail = "https://www.googleapis.com/auth/gmail.readonly"
        val calendar = "https://www.googleapis.com/auth/calendar.events.owned.readonly"
        val drive = "https://www.googleapis.com/auth/drive.metadata.readonly"
        val connector = TestConnector(
            id = "google",
            actions = listOf(
                action("gmail_list", "google_gmail", gmail),
                action("calendar_list", "google_calendar", calendar),
                action("drive_search", "google_drive", drive),
                ConnectorAgentAction(
                    id = "gmail_send",
                    description = "Write action requiring its own confirmation",
                    surfaceId = "google_gmail",
                    permission = ConnectorPermissionLevel.WRITE,
                    requiresConfirmation = true,
                    providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, "gmail.send")),
                ),
            ),
        )
        val registry = ConnectorRegistry()
        registry.register(connector)
        val profiles = InMemoryConnectorAccessProfileStore()

        assertTrue(ConnectorOAuthScopeResolver.requiredScopes(registry, profiles, "google").isEmpty())
        profiles.set("google_gmail", ConnectorAccessProfile.READ_ONLY)
        assertEquals(setOf(gmail), ConnectorOAuthScopeResolver.requiredScopes(registry, profiles, "google"))

        profiles.set("google_calendar", ConnectorAccessProfile.READ_ONLY)
        profiles.set("google_drive", ConnectorAccessProfile.READ_WRITE)
        assertEquals(setOf(gmail, calendar, drive), ConnectorOAuthScopeResolver.requiredScopes(registry, profiles, "google"))
    }

    @Test
    fun unknownRuntimeHasNoProviderScopes() {
        assertTrue(
            ConnectorOAuthScopeResolver.requiredScopes(
                ConnectorRegistry(),
                InMemoryConnectorAccessProfileStore(),
                "missing",
            ).isEmpty()
        )
    }

    private fun action(id: String, surfaceId: String, scope: String) = ConnectorAgentAction(
        id = id,
        description = id,
        surfaceId = surfaceId,
        providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, scope)),
    )

    private class TestConnector(
        override val id: String,
        private val actions: List<ConnectorAgentAction>,
    ) : Connector {
        override val name: String = id
        override val description: String = id
        override val type: ConnectorType = ConnectorType.APP
        private val state = MutableStateFlow(ConnectorState(connected = false))
        override fun meta() = ConnectorMeta(id, name, description, type)
        override fun agentActions() = actions
        override fun state() = state
        override suspend fun connect() = state.value
        override suspend fun disconnect() = Unit
        override suspend fun execute(input: ConnectorInput) = ConnectorOutput.Failure("unsupported", "test")
    }
}
