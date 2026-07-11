# voice — Full-Duplex Voice Pipeline

Owns the complete audio lifecycle for AIRI: wake word detection, speech-to-text, text-to-speech, and optional cloud realtime streaming.

## Architecture

```
HotwordService          Foreground service; listens for "Hey AIRI" wake word
  └── PorcupineEngine / OpenWakeWordEngine / VoskHotwordEngine

LiveVoiceService        Foreground service; owns the active voice session
  ├── LiveVoiceSession  State machine (idle → listening → processing → speaking)
  ├── VoiceManager      AudioFocusRequest lifecycle + TTS via IncrementalTtsEngine
  ├── VoiceAgentRouter  Routes STT results to direct agent or LLM fallback
  └── RealtimeVoiceProvider (selected via SharedPreferences)
        ├── LocalVoicePipeline    Null-object; delegates to Vosk + Android TTS
        ├── GeminiLiveProvider    Gemini BidiGenerateContent WebSocket
        └── OpenAIRealtimeProvider  OpenAI Realtime WebSocket

VoskEngine              On-device STT (no network, no key)
IncrementalTtsEngine    Streams TTS to AudioTrack as LLM tokens arrive
FullDuplexVadEngine     VAD for barge-in detection
```

## Lifecycle

1. App starts → `HotwordService` starts as foreground service
2. User navigates to chat → `ChatViewModel` binds to `LiveVoiceService`
3. On `onBind`: `restoreProviderPreference()` reads SharedPrefs and instantiates the saved provider
4. Voice button pressed → `binder.requestListen()` → Vosk STT or cloud audio stream
5. STT result → `VoiceAgentRouter` → `ProductionAgentOrchestrator`
6. LLM response → `IncrementalTtsEngine.append()` → streams to speaker
7. App backgrounded → service stays alive (foreground), mic released (audio focus abandoned)

## Provider Switching

Provider is selected in `VoiceSettingsScreen → CloudVoiceCard`. Selection:
1. Saves `cloud_voice_provider` pref (`LOCAL` | `GEMINI_LIVE` | `OPENAI_REALTIME`)
2. Calls `binder.setRealtimeProvider(provider)` immediately (live switch, no restart)
3. Persisted; restored on next `onBind`

## External Requirements

| Requirement | Provider | Status |
|-------------|---------|--------|
| Vosk model file (`vosk-model-small-en-us`) | Local | Required — bundled or downloaded on first launch |
| Picovoice AccessKey | Porcupine wake word | Optional — falls back to VoskHotword |
| Gemini API key | GeminiLiveProvider | Optional — key stored via SecureApiKeyStore |
| OpenAI API key | OpenAIRealtimeProvider | Optional — key stored via SecureApiKeyStore |

## Status

- Local pipeline (Vosk + Android TTS): **Production-ready**
- Gemini Live: **Wired** — requires Gemini API key with Live access enabled
- OpenAI Realtime: **Wired** — requires OpenAI API key with `gpt-4o-realtime` access
- Wake word: **Wired** — requires Picovoice AccessKey for Porcupine engine
