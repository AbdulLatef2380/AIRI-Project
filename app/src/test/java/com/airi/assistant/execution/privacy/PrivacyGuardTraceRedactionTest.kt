package com.airi.assistant.execution.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardTraceRedactionTest {

    @Test
    fun redactForTrace_removesCredentialsHeadersCookiesAndPaths() {
        val raw = """
            Authorization: Bearer super-secret-token-value-1234567890
            Cookie=session=private-cookie-value
            password = correct-horse-battery-staple
            api_key=AIza12345678901234567890123456789012345
            path=/data/user/0/com.airi.assistant/files/private.txt
        """.trimIndent()

        val redacted = PrivacyGuard.redactForTrace(raw)

        assertTrue(redacted.contains("[SECRET_REDACTED]"))
        assertTrue(redacted.contains("[KEY_REDACTED]"))
        assertTrue(redacted.contains("[PATH_REDACTED]"))
        assertFalse(redacted.contains("super-secret-token-value-1234567890"))
        assertFalse(redacted.contains("private-cookie-value"))
        assertFalse(redacted.contains("correct-horse-battery-staple"))
        assertFalse(redacted.contains("AIza12345678901234567890123456789012345"))
        assertFalse(redacted.contains("/data/user/0/com.airi.assistant/files/private.txt"))
    }

    @Test
    fun redactForTrace_enforcesACompactUpperBound() {
        val value = "x".repeat(500)

        val redacted = PrivacyGuard.redactForTrace(value, maximumChars = 40)

        assertTrue(redacted.length <= 70)
        assertTrue(redacted.contains("truncated by PrivacyGuard"))
    }
}
