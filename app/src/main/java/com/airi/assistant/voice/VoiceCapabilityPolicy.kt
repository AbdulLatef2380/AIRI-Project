package com.airi.assistant.voice

/** A truthful capability projection for the Voice Settings surface. */
internal object VoiceCapabilityPolicy {
    data class Snapshot(
        val wakeWordReady: Boolean,
        val speechRecognitionReady: Boolean,
        val voiceActivityDetectionReady: Boolean,
        val liveDuplexReady: Boolean
    )

    fun snapshot(
        wakeWordConfigured: Boolean,
        activeSpeechModelReady: Boolean,
        microphoneGranted: Boolean
    ): Snapshot = Snapshot(
        wakeWordReady = wakeWordConfigured,
        speechRecognitionReady = activeSpeechModelReady,
        voiceActivityDetectionReady = microphoneGranted,
        liveDuplexReady = false
    )
}
