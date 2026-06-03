# Phase 6 — Dead Code Isolation and Cleanup Report
*Verified-dead systems isolated. Runtime crash at startup eliminated. 0 functional regressions.*

---

## Goal

Safely remove or disable dead systems that were deferred from Phase 4 because
they required ServiceLocator restructuring, not just file deletion.

## Changes Made

### 1. ServiceLocator — Remove dead lazy initializers

**Removed (zero functional callers, confirmed Phase 4):**

| Property | LOC | Reason |
|---|---|---|
| `executionSnapshotStore` | 3 | Only used by executionGraphRuntime |
| `executionGraphRuntime` | 9 | Zero callers; only polled by executionWatchdog |
| `executionWatchdog` | 10 | Only polled executionGraphRuntime; never started on any real path |

**Associated imports commented out:**
- `ExecutionGraphRuntime`
- `ExecutionWatchdog`
- `SharedPreferencesSnapshotStore`

All three **classes are preserved on disk** for Phase 9 graph-native execution
roadmap. Only the ServiceLocator lazy-initialization wiring is removed.

### 2. AIRIApplication — Remove dead watchdog start call

```kotlin
// REMOVED:
ServiceLocator.executionWatchdog.start()
```

This call would have thrown `NullPointerException` at runtime after the
ServiceLocator property was removed. The crash was **guaranteed** on first
app launch if not fixed.

**Impact:** Eliminates a guaranteed startup crash that would have been
triggered after the Phase 6 ServiceLocator change.

### 3. AndroidManifest — Disable LiveVoiceService

`LiveVoiceService` added `android:enabled="false"` to prevent accidental
binding. The Phase 4 report confirmed the full realtime voice stack
(GeminiLiveProvider, OpenAIRealtimeProvider, FullDuplexVadEngine,
IncrementalTtsEngine, DuplexConversationRuntime) is unreachable — the
service is declared but never started.

`android:enabled="false"` means:
- The service is still declared (no manifest crash)
- It cannot be started by any Intent (safe)
- It will be re-enabled when the voice pipeline Phase 9 wiring is complete

---

## Why NOT Deleted (Dead Voice Stack)

The full realtime voice stack has 0 real start() callers but has **multiple
internal reference chains** between its own files:

```
LiveVoiceService → LiveVoiceSession → RealtimeVoiceProvider
LiveVoiceService → GeminiLiveProvider, OpenAIRealtimeProvider
LiveVoiceService → FullDuplexVadEngine, IncrementalTtsEngine, DuplexConversationRuntime
VoiceManager.kt → FullDuplexVadEngine  ← VoiceManager IS live (ChatScreen)
```

`FullDuplexVadEngine` is used by the live `VoiceManager` (which is called from
`ChatScreen`). Deleting the file would break the active Vosk STT pipeline.

**Decision:** Isolated at the manifest level (`android:enabled="false"`) rather
than deleted. The internal classes compile without modification. The voice
architecture teams can re-connect them to `VoiceAgentRouter` in Phase 9 without
any reconstruction work.

---

## Cleanup Totals

| Change | Type |
|---|---|
| `executionSnapshotStore` lazy property removed | ServiceLocator |
| `executionGraphRuntime` lazy property removed | ServiceLocator |
| `executionWatchdog` lazy property removed | ServiceLocator |
| 3 dead imports commented out | ServiceLocator |
| `executionWatchdog.start()` removed | AIRIApplication |
| `LiveVoiceService` disabled in manifest | AndroidManifest |

---

## Remaining Known Dead Code (NOT removed — preserved for Phase 9)

| System | Status | Future Path |
|---|---|---|
| `LiveVoiceService` stack | Disabled in manifest | Voice pipeline Phase 9 |
| `VoiceAgentRouter` | No real callers after LiveVoiceService disabled | Will be wired to `voiceTranscriptBus` in Phase 9 |
| `AgentObservabilityHub.attachVoiceSession()` | Never called | Will be wired in Phase 9 |
| `ExecutionGraphRuntime` class | Preserved on disk | Phase 9 graph-native execution |
| `AdaptiveGraphEngine` class | Preserved on disk | Phase 9 graph-native execution |
| `ExecutionWatchdog` class | Preserved on disk | Phase 9 graph-native execution |

---

## No Regressions

- ChatScreen voice (Vosk STT path via VoiceManager) — **unaffected**
- HotwordService — **unaffected** (separate microphone service, still enabled)
- AccessibilityService — **unaffected**
- All inference paths — **unaffected**
