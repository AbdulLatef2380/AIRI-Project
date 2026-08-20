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
    fun blankConnectorIsRejectedBeforeStateIssuance() {
        var thrown = false
        try {
            OAuthStateRegistry.issue("   ")
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
