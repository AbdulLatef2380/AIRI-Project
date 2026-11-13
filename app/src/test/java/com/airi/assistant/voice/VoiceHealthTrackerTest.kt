package com.airi.assistant.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceHealthTrackerTest {
    @Test
    fun recordsAggregateCountersAndLatency() {
        VoiceHealthTracker.reset()
        VoiceHealthTracker.sessionStarted()
        VoiceHealthTracker.wakeDetected()
        VoiceHealthTracker.firstAudioLatency(120)
        VoiceHealthTracker.error("microphone_busy")
        VoiceHealthTracker.sessionCompleted()

        val snapshot = VoiceHealthTracker.snapshot.value
        assertEquals(1, snapshot.sessions)
        assertEquals(1, snapshot.completedSessions)
        assertEquals(1, snapshot.wakeDetections)
        assertEquals(1, snapshot.errorCount)
        assertEquals(120, snapshot.averageFirstAudioLatencyMs)
        assertEquals("microphone_busy", snapshot.lastErrorCode)
        VoiceHealthTracker.reset()
    }
}
