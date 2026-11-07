package com.airi.assistant.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFactAdmissionPolicyTest {
    @Test
    fun rejectsCredentialLikeFactsBeforePromptInjection() {
        assertNull(MemoryFactAdmissionPolicy.sanitize("github api key = ghp_example"))
        assertNull(MemoryFactAdmissionPolicy.sanitize("password = secret"))
        assertNull(MemoryFactAdmissionPolicy.sanitize("Bearer token abc"))
    }

    @Test
    fun normalizesWhitespaceAndBoundsScratchpadFacts() {
        val normalized = MemoryFactAdmissionPolicy.sanitize("  language = Arabic   and English  ")
        assertEquals("language = Arabic and English", normalized)
        assertTrue(MemoryFactAdmissionPolicy.sanitize("x".repeat(1_000))!!.length <= 240)
    }

    @Test
    fun rejectsBlankFacts() {
        assertNull(MemoryFactAdmissionPolicy.sanitize("  \n\t "))
    }
}
