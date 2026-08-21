package com.airi.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RemoteControlSecurityPolicyTest {

    @Test
    fun `allows events below quota and rejects at quota`() {
        val limit = RemoteRateLimit(maxEvents = 2, windowMillis = 1_000)
        assertIs<RemoteRateLimitDecision.Allowed>(
            RemoteControlSecurityPolicy.decideRateLimit(listOf(500), limit, nowMillis = 1_000)
        )
        val rejected = assertIs<RemoteRateLimitDecision.Rejected>(
            RemoteControlSecurityPolicy.decideRateLimit(listOf(500, 900), limit, nowMillis = 1_000)
        )
        assertEquals(500, rejected.retryAfterMillis)
    }

    @Test
    fun `expires previous events outside rate window`() {
        assertIs<RemoteRateLimitDecision.Allowed>(
            RemoteControlSecurityPolicy.decideRateLimit(
                previousEventMillis = listOf(0, 200),
                limit = RemoteRateLimit(2, 1_000),
                nowMillis = 1_200
            )
        )
    }

    @Test
    fun `audit records identity outcome and type without command payload`() {
        val audit = RemoteControlSecurityPolicy.audit(
            eventId = "event-1",
            ownerId = "owner-1",
            deviceId = "desktop-1",
            commandType = RemoteControlCommandType.SUBMIT_TEXT_REQUEST,
            outcome = RemoteAuditEvent.Outcome.ACCEPTED,
            nowMillis = 1_000
        )

        assertEquals("command", audit.eventType)
        assertEquals(RemoteControlCommandType.SUBMIT_TEXT_REQUEST, audit.commandType)
        assertEquals(RemoteAuditEvent.Outcome.ACCEPTED, audit.outcome)
    }
}
