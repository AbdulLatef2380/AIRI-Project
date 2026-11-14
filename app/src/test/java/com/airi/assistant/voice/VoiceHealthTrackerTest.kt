package com.airi.assistant.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceHealthTrackerTest {
    @Test
    fun recordsAggregateCountersAndLatency() {
        val clock = FakeClock(1_000L)
        val tracker = VoiceHealthTrackerState(clock)
        tracker.sessionStarted()
        tracker.wakeDetected()
        tracker.firstAudioLatency(120)
        tracker.error("microphone_busy")
        clock.now = 1_275L
        tracker.sessionCompleted()

        val snapshot = tracker.snapshot.value
        assertEquals(1, snapshot.sessions)
        assertEquals(1, snapshot.completedSessions)
        assertEquals(1, snapshot.wakeDetections)
        assertEquals(1, snapshot.errorCount)
        assertEquals(120, snapshot.averageFirstAudioLatencyMs)
        assertEquals("microphone_busy", snapshot.lastErrorCode)
        assertEquals(275L, snapshot.lastSessionDurationMs)
        assertEquals(1_275L, snapshot.lastUpdatedElapsedMs)
    }

    private class FakeClock(var now: Long) : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = now
    }
}
