package com.airi.assistant.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRedactorTest {
    @Test
    fun redactsCredentialFormsButKeepsCommandShape() {
        val redacted = CommandRedactor.redact(
            "curl -H 'Authorization: Bearer abc.def' token=secret api_key=key123"
        )
        assertTrue(redacted.contains("Bearer [REDACTED]"))
        assertTrue(redacted.contains("token=[REDACTED]"))
        assertTrue(redacted.contains("api_key=[REDACTED]"))
        assertFalse(redacted.contains("abc.def"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("key123"))
    }
}
