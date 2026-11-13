package com.airi.assistant.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceExperiencePolicyTest {
    @Test
    fun invalidStoredValuesFailClosedToSafeDefaults() {
        assertEquals(VoiceExperiencePolicy.ResponseStyle.BALANCED, VoiceExperiencePolicy.parseResponseStyle("unknown"))
        assertEquals(VoiceExperiencePolicy.AlertSounds.SUBTLE, VoiceExperiencePolicy.parseAlertSounds("unknown"))
    }

    @Test
    fun longSpeechIsCondensedAtSentenceBoundaries() {
        val input = "First sentence. Second sentence. Third sentence. Fourth sentence."
        val result = SpeechTextPreprocessor.summarizeForSpeech(
            input,
            VoiceExperiencePolicy.ResponseStyle.CONCISE
        )
        assertTrue(result.length < input.length)
        assertTrue(result.startsWith("First sentence."))
    }
}
