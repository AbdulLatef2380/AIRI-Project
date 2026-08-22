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

    private fun testSession() = LiveVoiceSession(logger = { _, _ -> })
}
