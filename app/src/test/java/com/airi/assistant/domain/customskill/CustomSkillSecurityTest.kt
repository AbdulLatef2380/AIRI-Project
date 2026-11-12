package com.airi.assistant.domain.customskill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSkillSecurityTest {
    @Test
    fun allowsOnlyPublicHttpsEndpointsWithoutCredentialQueryNames() {
        assertTrue(CustomSkillSecurity.isValidEndpoint("https://api.example.com/v1/action?page=2"))
        listOf(
            "http://api.example.com/action",
            "https://127.0.0.1/action",
            "https://2130706433/action",
            "https://[::1]/action",
            "https://169.254.169.254/latest/meta-data/",
            "https://metadata.google.internal/computeMetadata/v1/",
            "https://user:password@api.example.com/action",
            "https://api.example.com/action?api_key=secret_value",
            "https://api.example.com/action?%61pi%5Fkey=secret_value",
            "https://api.example.com/action?access_token=secret_value",
        ).forEach { endpoint ->
            assertFalse("expected endpoint rejected: $endpoint", CustomSkillSecurity.isValidEndpoint(endpoint))
        }
    }

    @Test
    fun loggedEndpointContainsOnlyOriginAndNoPathOrQuery() {
        assertEquals(
            "https://api.example.com",
            CustomSkillSecurity.sanitizeUrl("https://user:password@api.example.com/private/abc?token=secret#fragment"),
        )
        assertEquals("[invalid-url]", CustomSkillSecurity.sanitizeUrl("not a URL"))
    }

    @Test
    fun sensitiveHeaderValuesAreMasked() {
        val headers = CustomSkillSecurity.maskHeaders(
            mapOf("Authorization" to "Bearer private-value", "X-Api-Key" to "private-key", "Accept" to "application/json")
        )
        assertEquals("****", headers["Authorization"])
        assertEquals("****", headers["X-Api-Key"])
        assertEquals("application/json", headers["Accept"])
    }
}
