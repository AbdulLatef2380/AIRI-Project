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

    @Test
    fun cloudInferenceCanBeReadyWithoutLocalInference() {
        val snapshot = VoiceCapabilityPolicy.snapshot(
            wakeWordConfigured = false,
            activeSpeechModelReady = true,
            microphoneGranted = true,
            textToSpeechReady = true,
            localInferenceReady = false,
            cloudInferenceReady = true
        )

        assertTrue(snapshot.inferenceReady)
        assertTrue(snapshot.cloudInferenceReady)
        assertFalse(snapshot.localInferenceReady)
        assertFalse(snapshot.liveDuplexReady)
    }

    @Test
    fun liveDuplexRequiresRealtimeTransportNotJustVoiceComponents() {
        val withoutTransport = VoiceCapabilityPolicy.snapshot(
            wakeWordConfigured = true,
            activeSpeechModelReady = true,
            microphoneGranted = true,
            textToSpeechReady = true,
            localInferenceReady = true,
            realtimeTransportReady = false
        )
        val withTransport = VoiceCapabilityPolicy.snapshot(
            wakeWordConfigured = true,
            activeSpeechModelReady = true,
            microphoneGranted = true,
            textToSpeechReady = true,
            localInferenceReady = true,
            realtimeTransportReady = true
        )

        assertFalse(withoutTransport.liveDuplexReady)
        assertTrue(withTransport.liveDuplexReady)
    }
}
