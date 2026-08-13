# Voice package

This package contains the active local voice pipeline: Vosk speech recognition, Android text-to-speech, wake-word services, voice-session state, and audio-focus handling.

## Active route

The supported chat route is local Vosk STT plus Android TTS. `LiveVoiceService` records whether listening was explicitly requested by the user, cancels delayed recovery after an explicit stop, and only resumes after an audio-focus gain when the user still requested listening. `HotwordService` applies a 2.5-second detection cooldown to avoid duplicate wake events.

## Realtime-provider limitation

`RealtimeVoiceProvider` defines contracts for Gemini and OpenAI realtime transports, but the PCM microphone capture and AudioTrack playback path is not wired end-to-end in `LiveVoiceService`. Cloud realtime voice is therefore not an active chat route and must not be advertised as one until live transport tests pass.

## External requirements

Local STT requires a compatible Vosk model and microphone permission. Wake word additionally needs a working OpenWakeWord asset or valid Picovoice setup. Voice behavior must be tested with real hardware, interruptions, Bluetooth devices, and Android background restrictions.
