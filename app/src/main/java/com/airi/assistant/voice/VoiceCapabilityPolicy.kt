package com.airi.assistant.voice

/** A truthful capability projection for the Voice Settings surface. */
object VoiceCapabilityPolicy {
    data class Snapshot(
        val wakeWordReady: Boolean,
        val speechRecognitionReady: Boolean,
        val voiceActivityDetectionReady: Boolean,
        val textToSpeechReady: Boolean,
        val localInferenceReady: Boolean,
        val cloudInferenceReady: Boolean,
        val realtimeTransportReady: Boolean,
        val liveDuplexReady: Boolean
    ) {
        val inferenceReady: Boolean
            get() = localInferenceReady || cloudInferenceReady
    }

    fun snapshot(
        wakeWordConfigured: Boolean,
        activeSpeechModelReady: Boolean,
        microphoneGranted: Boolean,
        textToSpeechReady: Boolean = false,
        localInferenceReady: Boolean = activeSpeechModelReady,
        cloudInferenceReady: Boolean = false,
        realtimeTransportReady: Boolean = false
    ): Snapshot = Snapshot(
        wakeWordReady = wakeWordConfigured,
        speechRecognitionReady = activeSpeechModelReady,
        voiceActivityDetectionReady = microphoneGranted,
        textToSpeechReady = textToSpeechReady,
        localInferenceReady = localInferenceReady,
        cloudInferenceReady = cloudInferenceReady,
        realtimeTransportReady = realtimeTransportReady,
        // Live duplex is a bidirectional audio transport capability. Local
        // speech recognition/TTS components alone do not provide that
        // transport, so the realtime channel must be explicitly ready.
        liveDuplexReady = microphoneGranted && textToSpeechReady && realtimeTransportReady
    )
}
