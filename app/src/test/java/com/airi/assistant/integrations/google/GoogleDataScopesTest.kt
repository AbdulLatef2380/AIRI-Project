package com.airi.assistant.integrations.google

import com.airi.assistant.connector.ConnectorProviderScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDataScopesTest {
    @Test
    fun surfaceScopeSetsAreIndependent() {
        assertEquals(
            setOf(ConnectorProviderScopes.GOOGLE_GMAIL_READONLY),
            GoogleDataScopes.scopesForSurface("google_gmail"),
        )
        assertEquals(
            setOf(ConnectorProviderScopes.GOOGLE_DRIVE_METADATA_READONLY),
            GoogleDataScopes.scopesForSurface("google_drive"),
        )
        assertFalse(GoogleDataScopes.scopesForSurface("google_gmail").intersect(
            GoogleDataScopes.scopesForSurface("google_drive")
        ).isNotEmpty())
    }

    @Test
    fun unknownOrCrossSurfaceScopeRequestsAreRejected() {
        val gmail = ConnectorProviderScopes.GOOGLE_GMAIL_READONLY
        val drive = ConnectorProviderScopes.GOOGLE_DRIVE_METADATA_READONLY
        assertTrue(GoogleDataScopes.isValidSurfaceRequest("google_gmail", setOf(gmail)))
        assertFalse(GoogleDataScopes.isValidSurfaceRequest("google_gmail", setOf(gmail, drive)))
        assertFalse(GoogleDataScopes.isValidSurfaceRequest("unknown", setOf(gmail)))
        assertFalse(GoogleDataScopes.isValidSurfaceRequest("google_gmail", emptySet()))
    }
}
