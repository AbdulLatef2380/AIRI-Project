package com.airi.assistant.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseTerminalPolicyTest {
    @Test
    fun blankAndWhitespaceResponsesAreNotSuccessful() {
        assertFalse(ResponseTerminalPolicy.isSuccessful(""))
        assertFalse(ResponseTerminalPolicy.isSuccessful(" \n\t "))
    }

    @Test
    fun nonBlankResponseIsSuccessful() {
        assertTrue(ResponseTerminalPolicy.isSuccessful("hello"))
        assertTrue(ResponseTerminalPolicy.isSuccessful("  hello  "))
    }
}
