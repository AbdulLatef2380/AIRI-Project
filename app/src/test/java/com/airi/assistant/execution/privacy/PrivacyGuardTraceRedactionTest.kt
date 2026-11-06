package com.airi.assistant.execution.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardTraceRedactionTest {

    @Test
    fun redactForTrace_removesCredentialsHeadersCookiesAndPaths() {
        val raw = """
            Authorization: Bearer super-secret-token-value-1234567890
            Cookie=session=private-cookie-value
            password = correct-horse-battery-staple
            api_key=fixture-google-key-value-1234567890
            path=/data/user/0/com.airi.assistant/files/private.txt
        """.trimIndent()

        val redacted = PrivacyGuard.redactForTrace(raw)

        assertTrue(redacted.contains("[SECRET_REDACTED]"))
        assertTrue(redacted.contains("[KEY_REDACTED]"))
        assertTrue(redacted.contains("[PATH_REDACTED]"))
        assertFalse(redacted.contains("super-secret-token-value-1234567890"))
        assertFalse(redacted.contains("private-cookie-value"))
        assertFalse(redacted.contains("correct-horse-battery-staple"))
        assertFalse(redacted.contains("fixture-google-key-value-1234567890"))
        assertFalse(redacted.contains("/data/user/0/com.airi.assistant/files/private.txt"))
    }

    @Test
    fun redactForTrace_enforcesACompactUpperBound() {
        val value = "x".repeat(500)

        val redacted = PrivacyGuard.redactForTrace(value, maximumChars = 40)

        assertTrue(redacted.length <= 70)
        assertTrue(redacted.contains("truncated by PrivacyGuard"))
    }

    @Test
    fun redactForTrace_removesShortBearerPiiPaymentCardsAndWindowsPaths() {
        val raw = "Bearer abc123 email=user@example.com phone=+1 202-555-0199 " +
            "card=4111 1111 1111 1111 file=C:\\Users\\Alice\\secret.txt"

        val redacted = PrivacyGuard.redactForTrace(raw)

        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("user@example.com"))
        assertFalse(redacted.contains("202-555-0199"))
        assertFalse(redacted.contains("4111 1111 1111 1111"))
        assertFalse(redacted.contains("C:\\Users\\Alice\\secret.txt"))
    }

    @Test
    fun redactForTrace_removesJwtAndGenericTokenAssignments() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature-value"
        val raw = "token=short-token-value access_token=access-value client_secret=client-value jwt=$jwt"

        val redacted = PrivacyGuard.redactForTrace(raw)

        assertFalse(redacted.contains("short-token-value"))
        assertFalse(redacted.contains("access-value"))
        assertFalse(redacted.contains("client-value"))
        assertFalse(redacted.contains(jwt))
    }

    @Test
    fun redactTraceField_blocksSensitiveFieldNamesEvenForShortValues() {
        val sensitive = listOf(
            "access_token", "refresh-token", "client_secret", "webhook_key", "api-key"
        )

        sensitive.forEach { field ->
            assertEquals("[SECRET_REDACTED]", PrivacyGuard.redactTraceField(field, "x"))
        }
    }
}
