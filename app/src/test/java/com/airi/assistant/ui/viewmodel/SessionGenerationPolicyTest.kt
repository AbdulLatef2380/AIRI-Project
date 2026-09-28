package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGenerationPolicyTest {
    @Test
    fun responseOnlyPublishesIntoItsOriginatingSession() {
        assertTrue(SessionGenerationPolicy.mayPublishToVisibleSession("session-A", "session-A"))
        assertFalse(SessionGenerationPolicy.mayPublishToVisibleSession("session-A", "session-B"))
        assertFalse(SessionGenerationPolicy.mayPublishToVisibleSession("", "session-B"))
    }

    @Test
    fun localConversationHistoryCanOnlyBeReplacedWhenNoGenerationOwnsIt() {
        assertTrue(SessionGenerationPolicy.mayReplaceNativeHistory(null))
        assertFalse(SessionGenerationPolicy.mayReplaceNativeHistory("session-A"))
    }
}
