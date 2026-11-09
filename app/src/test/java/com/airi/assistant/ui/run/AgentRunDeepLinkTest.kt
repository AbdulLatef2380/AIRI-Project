package com.airi.assistant.ui.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunDeepLinkTest {
    @Test fun parsesWellFormedLink() {
        assertEquals("abc-123", AgentRunDeepLink.parse("airi://agent/run/abc-123"))
        assertEquals("abc-123", AgentRunDeepLink.parse("airi://agent/run/abc-123/?x=1#f"))
    }

    @Test fun roundTripsBuild() {
        assertEquals("r_1", AgentRunDeepLink.parse(AgentRunDeepLink.build("r_1")))
    }

    @Test fun rejectsMalformedOrUnsafe() {
        assertNull(AgentRunDeepLink.parse(null))
        assertNull(AgentRunDeepLink.parse("airi://agent/run/"))
        assertNull(AgentRunDeepLink.parse("airi://oauth/callback"))
        assertNull(AgentRunDeepLink.parse("https://agent/run/abc"))
        assertNull(AgentRunDeepLink.parse("airi://agent/run/../secret"))
        assertNull(AgentRunDeepLink.parse("airi://agent/run/" + "a".repeat(200)))
    }

    @Test fun pendingIsConsumedOnce() {
        AgentRunDeepLink.offer("airi://agent/run/x1")
        assertEquals("x1", AgentRunDeepLink.pending.value)
        AgentRunDeepLink.consume("other")
        assertEquals("x1", AgentRunDeepLink.pending.value)
        AgentRunDeepLink.consume("x1")
        assertNull(AgentRunDeepLink.pending.value)
    }
}
