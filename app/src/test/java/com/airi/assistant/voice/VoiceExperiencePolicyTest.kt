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
        val input = listOf(
            "First sentence.",
            "Second sentence ${"b".repeat(260)}.",
            "Third sentence ${"c".repeat(260)}.",
            "Fourth sentence ${"d".repeat(260)}.",
        ).joinToString(" ")
        val result = SpeechTextPreprocessor.summarizeForSpeech(
            input,
            VoiceExperiencePolicy.ResponseStyle.CONCISE
        )
        assertTrue(result.length < input.length)
        assertTrue(result.startsWith("First sentence."))
    }
}
