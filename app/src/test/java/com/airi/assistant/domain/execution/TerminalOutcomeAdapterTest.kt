package com.airi.assistant.domain.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutcomeAdapterTest {
    @Test
    fun publishesSuccessOnlyOnce() {
        val events = mutableListOf<TerminalOutcome>()
        val adapter = TerminalOutcomeAdapter(events::add)

        assertTrue(adapter.publish(TerminalStatus.SUCCESS))
        assertFalse(adapter.publish(TerminalStatus.FAILURE, "late failure"))
        assertEquals(listOf(TerminalOutcome(TerminalStatus.SUCCESS)), events)
    }

    @Test
    fun preservesFailureTimeoutAndCancellationReasons() {
        val events = mutableListOf<TerminalOutcome>()
        listOf(
            TerminalStatus.FAILURE to "handler failed",
            TerminalStatus.TIMEOUT to "deadline exceeded",
            TerminalStatus.CANCELLED to "user stopped",
        ).forEach { (status, reason) ->
            val adapter = TerminalOutcomeAdapter(events::add)
            assertTrue(adapter.publish(status, reason))
            assertEquals(TerminalOutcome(status, reason), adapter.outcome())
        }
        assertEquals(3, events.size)
        assertEquals(TerminalStatus.FAILURE, events[0].status)
        assertEquals(TerminalStatus.TIMEOUT, events[1].status)
        assertEquals(TerminalStatus.CANCELLED, events[2].status)
    }

    @Test
    fun concurrentPublishStillDeliversOneTerminalEvent() {
        val events = java.util.Collections.synchronizedList(mutableListOf<TerminalOutcome>())
        val adapter = TerminalOutcomeAdapter(events::add)
        val threads = (1..16).map { Thread { adapter.publish(TerminalStatus.FAILURE, "race-$it") } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(1, events.size)
        assertEquals(TerminalStatus.FAILURE, requireNotNull(adapter.outcome()).status)
    }
}
