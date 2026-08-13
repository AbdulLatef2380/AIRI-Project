from pathlib import Path

root = Path('/home/ubuntu/AIRI-Project')
readmes = {
    'app/src/main/java/com/airi/assistant/agent/README.md': '''# Agent package

This package owns planning, sub-agents, execution loops, and durable scheduled work.

## Active behavior

`AgentLoop` delegates model execution to `HybridOrchestrator`. Background tasks are created through `ScheduledJobOrchestrator` and executed by `ScheduledAgentWorker`. One-time and periodic task metadata is persisted locally, including whether network access is required and the last recorded outcome (`PENDING`, `COMPLETED`, or `FAILED`).

## Safety and limitations

A background task is not an interactive UI session. It has a bounded execution budget and must not assume foreground permissions or user interaction. Worker domain failures are recorded for the task UI; they are not blindly retried as infrastructure failures.

## Verification

The scheduler persistence and worker-result paths are covered by the project static verifier. WorkManager behavior, Doze timing, and device reboot restoration still require instrumentation and physical-device testing.
''',
    'app/src/main/java/com/airi/assistant/connector/README.md': '''# Connector package

This package contains adapters and configuration for external services.

## Current contract

A connector must expose its real availability state before the UI suggests it as usable. Secrets belong in encrypted storage and must never be logged. Any action with external side effects requires an explicit user confirmation in the calling UI.

## Limitations

Provider integrations depend on user credentials, network availability, provider policy, and live API verification. A visible integration or preference is not evidence that the external service has been successfully exercised.

## Verification

Build-time and live-credential verification are pending in this workspace because Android Gradle Plugin dependencies cannot currently be resolved from the configured repositories.
''',
    'app/src/main/java/com/airi/assistant/memory/README.md': '''# Memory package

This package owns Room-backed chat history, long-term memory admission, embeddings, and retrieval-augmented context.

## Memory model

Conversation history and durable memory are separate. Recent normal chat rows are bounded per session. `MemoryAdmissionPolicy` decides whether a turn is eligible for embedding and rejects transient, oversized, and sensitive content. Durable extracted facts require an explicit user memory request and are restricted to non-sensitive preference, dislike, language, and project categories.

`EmbeddingService` performs semantic search only inside the current session. `RagRetriever` combines bounded semantic hits with explicit long-term memory and labels all injected content as untrusted historical reference data. It must not be treated as instructions.

## Limits and follow-up

Memory policy detection is heuristic, not a substitute for a complete PII classifier. SQLCipher migration and all Room migrations require real-device validation before a release claim. The build version and implementation details must be checked in `AiriDatabase.kt`, not inferred from historical reports.

## Verification

Static verification covers admission-policy use, session-scoped vector retrieval, and RAG prompt framing. Full Room migration and device-performance tests remain pending.
''',
    'app/src/main/java/com/airi/assistant/security/README.md': '''# Security package

This package contains application security and privacy controls.

## Current principles

Credentials must be stored through the project encrypted-storage path. Dynamic custom skills require an explicit HTTPS endpoint; a placeholder endpoint is rejected. The profile deletion flow uses `DataDeletionCoordinator`, which coordinates background-work cancellation, account deletion, local data cleanup, credential cleanup, preference reset, cache cleanup, and sign-out.

## Limits

Security depends on correct Android Keystore availability, external-provider restrictions, and runtime tests. Do not claim that a provider key, SQLCipher migration, Play Integrity result, or network transport is secure solely because code paths exist. Verify them on release candidates.

## Verification

Static checks confirm that the profile screen does not call Firebase-only deletion directly and that dynamic-skill registration rejects a missing HTTPS endpoint.
''',
    'app/src/main/java/com/airi/assistant/ui/README.md': '''# UI package

This package owns AIRI's Compose screens, navigation, theming, localization resources, and interaction state.

## Chat input

The active composer supports `/` for enabled, connected skills and `@` for current-session saved knowledge. A selection is represented internally as a directive, revalidated by the ViewModel, and removed from the visible user prompt before execution. The stop control cancels the current generation owner rather than merely changing the icon state.

## RTL and localization

New input text uses logical `TextAlign.Start`, allowing Android layout direction to control Arabic and left-to-right presentation. The shortcut knowledge label is present in English and Arabic resources. Other locales fall back to the default resource until translated.

## Limits

The project contains historical hard-coded text and styling outside the focused paths. Screen-level visual, accessibility, dark-mode, and RTL validation must be performed on devices and with Compose tests before release.
''',
    'app/src/main/java/com/airi/assistant/voice/README.md': '''# Voice package

This package contains the active local voice pipeline: Vosk speech recognition, Android text-to-speech, wake-word services, voice-session state, and audio-focus handling.

## Active route

The supported chat route is local Vosk STT plus Android TTS. `LiveVoiceService` records whether listening was explicitly requested by the user, cancels delayed recovery after an explicit stop, and only resumes after an audio-focus gain when the user still requested listening. `HotwordService` applies a 2.5-second detection cooldown to avoid duplicate wake events.

## Realtime-provider limitation

`RealtimeVoiceProvider` defines contracts for Gemini and OpenAI realtime transports, but the PCM microphone capture and AudioTrack playback path is not wired end-to-end in `LiveVoiceService`. Cloud realtime voice is therefore not an active chat route and must not be advertised as one until live transport tests pass.

## External requirements

Local STT requires a compatible Vosk model and microphone permission. Wake word additionally needs a working OpenWakeWord asset or valid Picovoice setup. Voice behavior must be tested with real hardware, interruptions, Bluetooth devices, and Android background restrictions.
''',
}

for relative, content in readmes.items():
    path = root / relative
    path.write_text(content.rstrip() + '\n', encoding='utf-8')
    print(f'updated {relative}')
