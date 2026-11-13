package com.airi.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVoiceSessionTest {

    @Test
    fun legalTurnFlowReachesIdleAfterCompletion() {
        val session = testSession()

        session.beginSession()
        session.onSpeechResult("hello")
        session.onResponseStreaming(sttToFirstTokenMs = 120)
        session.onTurnComplete()

        assertEquals(VoicePipelineState.IDLE, session.state.value)
        assertEquals(1, session.metrics.value.completedTurns)
    }

    @Test
    fun streamingCannotStartWithoutListeningAndThinking() {
        val session = testSession()

        session.onResponseStreaming(sttToFirstTokenMs = 20)

        assertEquals(VoicePipelineState.IDLE, session.state.value)
    }

    @Test
    fun bargeInCanOnlyResumeListeningAfterResponseStream() {
        val session = testSession()
        session.beginSession()
        session.onSpeechResult("hello")
        session.onResponseStreaming(sttToFirstTokenMs = 20)
        session.onBargeIn()
        session.onResumeListening()

        assertEquals(VoicePipelineState.LISTENING, session.state.value)
        assertEquals(1, session.metrics.value.interruptionCount)
    }

    @Test
    fun endingSessionInvalidatesItsSessionIdAndLateTranscript() {
        val session = testSession()
        val id = session.beginSession()

        session.endSession()

        assertEquals(0L, session.currentSessionId)
        assertEquals(false, session.isActive(id))
        assertEquals(false, session.onSpeechResult("late", id))
        assertEquals("", session.partialTranscript.value)
    }

    @Test
    fun aNewSessionRejectsTranscriptFromAnOlderSession() {
        val session = testSession()
        val oldId = session.beginSession()
        session.endSession()
        val newId = session.beginSession()

        assertEquals(false, session.onPartialTranscript("old", oldId))
        assertEquals(true, session.onPartialTranscript("new", newId))
        assertEquals("new", session.partialTranscript.value)
    }

    @Test
    fun transcriptIsRejectedAfterTheTurnHasEnteredThinking() {
        val session = testSession()
        val id = session.beginSession()

        assertEquals(true, session.onSpeechResult("first", id))
        assertEquals(false, session.onPartialTranscript("late partial", id))
        assertEquals(false, session.onSpeechResult("duplicate", id))
        assertEquals("first", session.partialTranscript.value)
    }

    private fun testSession() = LiveVoiceSession(logger = { _, _ -> })
}
