package com.airi.assistant.connector

import com.airi.assistant.connector.api.SecretRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    // Constructed in pieces so repository secret scanners do not mistake this
    // deterministic test fixture for a credential literal.
    private val key = "AIza" + "SyA1234567890" + "abcdefghijklmnopqrstu"

    @Test fun removesExactKey() {
        val out = SecretRedactor.redact("bad request for $key", key)
        assertFalse(out.contains(key)); assertTrue(out.contains("***"))
    }

    @Test fun removesQueryParameterEvenWithoutKnownSecret() {
        val out = SecretRedactor.redact("GET https://x/y?alt=sse&key=abc123XYZ&z=1")
        assertFalse(out.contains("abc123XYZ")); assertTrue(out.contains("z=1"))
    }

    @Test fun removesHeaderEcho() {
        val out = SecretRedactor.redact("""{"x-goog-api-key": "secretvalue123"}""")
        assertFalse(out.contains("secretvalue123"))
    }

    @Test fun removesGoogleKeyShapeWithoutContext() {
        assertFalse(SecretRedactor.redact("oops $key oops").contains(key))
    }

    @Test fun leavesNormalTextAlone() {
        val t = "gemini http 429: quota exceeded"
        assertTrue(SecretRedactor.redact(t, key) == t)
    }
}
