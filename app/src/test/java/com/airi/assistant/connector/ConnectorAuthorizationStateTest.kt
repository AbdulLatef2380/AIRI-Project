package com.airi.assistant.connector

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorAuthorizationStateTest {
    @Test
    fun projection_reports_profile_identity_and_scope_reason_without_secret() {
        val connector = FixtureConnector(connected = false, healthy = false)
        val action = ConnectorAgentAction(
            id = "read",
            description = "Read",
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, "repo")),
        )
        val state = ConnectorAuthorizationProjection.forAction(connector, action, InMemoryConnectorAccessProfileStore())
        assertEquals(ConnectorAuthorizationState.AuthorizationStatus.PROFILE_REQUIRED, state.status)
        assertEquals("profile_required", state.reasonCode)
        assertFalse(state.isAuthorized)
        assertTrue(state.requiredScopes.contains("repo"))
        assertTrue(state.surfaces.single().surfaceId == "fixture")
    }

    @Test
    fun granted_surface_is_not_confused_with_connected_identity() {
        val profiles = InMemoryConnectorAccessProfileStore().apply { set("fixture", ConnectorAccessProfile.READ_ONLY) }
        val action = ConnectorAgentAction("read", "Read", providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, "repo")))
        val state = ConnectorAuthorizationProjection.forAction(FixtureConnector(false, false), action, profiles)
        assertEquals(ConnectorAuthorizationState.AuthorizationStatus.IDENTITY_REQUIRED, state.status)
        assertFalse(state.surfaces.single().authorized)
        assertEquals("identity_required", state.reasonCode)
    }

    @Test
    fun explicit_provider_scopes_are_checked_instead_of_inferred_from_health() {
        val profiles = InMemoryConnectorAccessProfileStore().apply { set("fixture", ConnectorAccessProfile.READ_WRITE) }
        val action = ConnectorAgentAction(
            "write",
            "Write",
            permission = ConnectorPermissionLevel.WRITE,
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, "repo:write")),
        )
        val state = ConnectorAuthorizationProjection.forAction(
            FixtureConnector(connected = true, healthy = true, grantedScopes = setOf("repo:read")),
            action,
            profiles,
        )
        assertEquals(ConnectorAuthorizationState.AuthorizationStatus.SCOPE_REQUIRED, state.status)
        assertEquals("oauth_scope_required", state.reasonCode)
        assertFalse(state.isAuthorized)
        assertTrue(state.reason!!.contains("repo:write"))
    }

    private class FixtureConnector(
        private val connected: Boolean,
        private val healthy: Boolean,
        grantedScopes: Set<String>? = null,
    ) : Connector {
        override val id = "fixture"
        override val name = "fixture"
        override val description = "fixture"
        override val type = ConnectorType.APP
        private val flow = MutableStateFlow(ConnectorState(connected, healthy, grantedScopes = grantedScopes))
        override fun meta() = ConnectorMeta(id, name, description, type)
        override fun state() = flow
        override suspend fun connect() = flow.value
        override suspend fun disconnect() { flow.value = ConnectorState(false, false) }
        override suspend fun execute(input: ConnectorInput) = ConnectorOutput.Success("ok")
    }
}
