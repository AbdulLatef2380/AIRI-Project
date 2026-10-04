package com.airi.assistant.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorAccessProfileTest {
    @Test
    fun unconfigured_profile_denies_every_permission() {
        ConnectorPermissionLevel.values().forEach { permission ->
            assertFalse(ConnectorAccessProfile.NOT_CONFIGURED.permits(permission))
        }
    }

    @Test
    fun profiles_are_monotonic_but_cannot_skip_confirmation() {
        assertTrue(ConnectorAccessProfile.READ_ONLY.permits(ConnectorPermissionLevel.READ))
        assertFalse(ConnectorAccessProfile.READ_ONLY.permits(ConnectorPermissionLevel.WRITE))
        assertTrue(ConnectorAccessProfile.READ_WRITE.permits(ConnectorPermissionLevel.WRITE))
        assertFalse(ConnectorAccessProfile.READ_WRITE.permits(ConnectorPermissionLevel.DESTRUCTIVE))
        assertTrue(ConnectorAccessProfile.FULL_ACCESS.permits(ConnectorPermissionLevel.ADMIN))

        val confirmedWrite = ConnectorAgentAction(
            id = "write",
            description = "Write action",
            permission = ConnectorPermissionLevel.WRITE,
            requiresConfirmation = true,
        )
        assertEquals(
            ConnectorAccessDecision.CONFIRMATION_REQUIRED,
            ConnectorAccessPolicy.evaluate(ConnectorAccessProfile.READ_WRITE, confirmedWrite),
        )
        assertEquals(
            ConnectorAccessDecision.NOT_GRANTED,
            ConnectorAccessPolicy.evaluate(ConnectorAccessProfile.READ_ONLY, confirmedWrite),
        )
        val unconfirmedAdmin = ConnectorAgentAction(
            id = "admin",
            description = "Admin action",
            permission = ConnectorPermissionLevel.ADMIN,
            requiresConfirmation = false,
        )
        assertEquals(
            ConnectorAccessDecision.CONFIRMATION_REQUIRED,
            ConnectorAccessPolicy.evaluate(ConnectorAccessProfile.FULL_ACCESS, unconfirmedAdmin),
        )
    }

    @Test
    fun in_memory_profile_store_isolated_and_revoke_removes_grant() {
        val store = InMemoryConnectorAccessProfileStore()
        assertEquals(ConnectorAccessProfile.NOT_CONFIGURED, store.get("github"))
        store.set("github", ConnectorAccessProfile.READ_WRITE)
        assertEquals(ConnectorAccessProfile.READ_WRITE, store.get("github"))
        assertEquals(mapOf("github" to ConnectorAccessProfile.READ_WRITE), store.all())
        store.set("github", ConnectorAccessProfile.NOT_CONFIGURED)
        assertEquals(ConnectorAccessProfile.NOT_CONFIGURED, store.get("github"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun providerGrantsAreNotMisreportedAsOAuthScopes() {
        val action = ConnectorAgentAction(
            id = "get_updates",
            description = "Read Telegram updates",
            providerGrants = listOf(ConnectorProviderGrant(
                ConnectorProviderGrantKind.TELEGRAM_BOT_TOKEN_CAPABILITY,
                "Valid bot token and updates delivered to bot",
            )),
        )

        assertTrue(action.requiredOAuthScopes.isEmpty())
        assertEquals(ConnectorProviderGrantKind.TELEGRAM_BOT_TOKEN_CAPABILITY, action.providerGrants.single().kind)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blank_surface_id_cannot_receive_a_grant() {
        InMemoryConnectorAccessProfileStore().set(" ", ConnectorAccessProfile.FULL_ACCESS)
    }
}
