package com.airi.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapabilityPolicyTest {
    @Test
    fun projectsOnlyConfiguredAndPermittedCapabilitiesAsReady() {
        val ready = VoiceCapabilityPolicy.snapshot(
            wakeWordConfigured = true,
            activeSpeechModelReady = true,
            microphoneGranted = true
        )

        assertTrue(ready.wakeWordReady)
        assertTrue(ready.speechRecognitionReady)
        assertTrue(ready.voiceActivityDetectionReady)
        assertFalse(ready.liveDuplexReady)
    }

    @Test
    fun keepsMissingModelOrPermissionCapabilitiesUnavailable() {
        val unavailable = VoiceCapabilityPolicy.snapshot(
            wakeWordConfigured = false,
            activeSpeechModelReady = false,
            microphoneGranted = false
        )

        assertFalse(unavailable.wakeWordReady)
        assertFalse(unavailable.speechRecognitionReady)
        assertFalse(unavailable.voiceActivityDetectionReady)
        assertFalse(unavailable.liveDuplexReady)
    }
}
