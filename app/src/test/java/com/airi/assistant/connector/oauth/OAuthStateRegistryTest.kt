package com.airi.assistant.connector.oauth

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthStateRegistryTest {

    @Test
    fun issuedStatesAreUniqueAndConsumedExactlyOnce() {
        val first = OAuthStateRegistry.issue("drive")
        val second = OAuthStateRegistry.issue("drive")

        assertNotEquals(first, second)
        assertTrue(OAuthStateRegistry.isPending(first))
        assertEquals("drive", OAuthStateRegistry.consume(first))
        assertFalse(OAuthStateRegistry.isPending(first))
        assertNull(OAuthStateRegistry.consume(first))
        assertEquals("drive", OAuthStateRegistry.consume(second))
    }

    @Test
    fun unknownStateIsRejectedWithoutAConnector() {
        assertNull(OAuthStateRegistry.consume("unknown-callback-state"))
        assertNull(OAuthStateRegistry.consumeRequest("unknown-callback-state"))
    }

    @Test
    fun pkceChallengeMatchesTheConsumedVerifier() {
        val authorization = OAuthStateRegistry.issuePkce("notion")

        assertTrue(authorization.state.isNotBlank())
        assertFalse(authorization.state.contains('='))
        assertTrue(OAuthStateRegistry.isPending(authorization.state))

        val consumed = OAuthStateRegistry.consumeRequest(authorization.state)
        assertEquals("notion", consumed?.connectorId)
        val verifier = requireNotNull(consumed?.codeVerifier)
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expectedChallenge, authorization.codeChallenge)
        assertNull(OAuthStateRegistry.consumeRequest(authorization.state))
    }

    @Test
    fun requestedScopesAreBoundToTheSingleUsePkceState() {
        val scopes = setOf("Mail.ReadBasic", "User.Read")
        val authorization = OAuthStateRegistry.issuePkce("microsoft_graph", scopes)

        val consumed = OAuthStateRegistry.consumeRequest(authorization.state)
        assertEquals("microsoft_graph", consumed?.connectorId)
        assertEquals(scopes, consumed?.requestedScopes)
        assertNull(OAuthStateRegistry.consumeRequest(authorization.state))
    }

    @Test
    fun pkceStateBindsGithubProjectAndCannotBeReplayed() {
        val scopes = setOf("repo:read")
        val authorization = OAuthStateRegistry.issuePkce(
            connectorId = "github",
            requestedScopes = scopes,
            ownerBinding = "project-one",
        )

        val consumed = OAuthStateRegistry.consumeRequest(authorization.state)
        assertEquals("github", consumed?.connectorId)
        assertEquals("project-one", consumed?.ownerBinding)
        assertEquals(scopes, consumed?.requestedScopes)
        assertTrue(requireNotNull(consumed?.codeVerifier).isNotBlank())
        assertNull(OAuthStateRegistry.consumeRequest(authorization.state))
    }

    @Test
    fun pendingRouteExposesOnlyOwnerMetadataAndDoesNotConsumePkceState() {
        val authorization = OAuthStateRegistry.issuePkce(
            connectorId = "github",
            ownerBinding = "project-one",
        )

        assertEquals(
            OAuthStateRegistry.PendingRoute("github", "project-one"),
            OAuthStateRegistry.pendingRoute(authorization.state),
        )
        assertTrue(OAuthStateRegistry.isPending(authorization.state))
        assertEquals("project-one", OAuthStateRegistry.consumeRequest(authorization.state)?.ownerBinding)
        assertNull(OAuthStateRegistry.pendingRoute(authorization.state))
    }

    @Test
    fun dropboxPkceStateBindsTheGrantedDropboxScopes() {
        val scopes = setOf("account_info.read", "files.metadata.read", "files.content.read")
        val authorization = OAuthStateRegistry.issuePkce("dropbox", scopes)
        val consumed = OAuthStateRegistry.consumeRequest(authorization.state)
        assertEquals("dropbox", consumed?.connectorId)
        assertEquals(scopes, consumed?.requestedScopes)
        assertTrue(requireNotNull(consumed?.codeVerifier).length in 43..128)
        assertTrue(authorization.codeChallenge.length in 43..128)
    }

    @Test
    fun boxConfidentialStateBindsReadOnlyScopeAndIsSingleUse() {
        val state = OAuthStateRegistry.issueWithScopes("box", setOf("root_readonly"))
        val consumed = OAuthStateRegistry.consumeRequest(state)
        assertEquals("box", consumed?.connectorId)
        assertEquals(setOf("root_readonly"), consumed?.requestedScopes)
        assertNull(OAuthStateRegistry.consumeRequest(state))
    }

    @Test
    fun blankConnectorIsRejectedBeforeStateIssuance() {
        var thrown = false
        try {
            OAuthStateRegistry.issue("   ")
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun ttlPolicyAcceptsOnlyNonNegativeAgeWithinInclusiveBoundary() {
        assertTrue(OAuthStateTtlPolicy.isFresh(1_000L, 1_000L, 300_000L))
        assertTrue(OAuthStateTtlPolicy.isFresh(1_000L, 301_000L, 300_000L))
        assertFalse(OAuthStateTtlPolicy.isFresh(1_000L, 301_001L, 300_000L))
        assertFalse(OAuthStateTtlPolicy.isFresh(1_000L, 999L, 300_000L))
    }
}
