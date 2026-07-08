---
name: Wave 3-4 Activation Decisions
description: Key API quirks and architecture decisions from Wave 3 and Wave 4 activation implementation.
---

## Critical API gotchas

**VoicePreferencesStore** — No `currentSnapshot()` method exists. Use `VoicePreferencesStore.snapshotFlow.value` to get the current snapshot. May be null on cold start (before `load(context)` is called). Always null-safe the access.

**SpeculativeManager** — Method is `stats()` (not `getStats()`). Returns `SpecStats(drafted, accepted, runs)` with computed `acceptanceRate: Float`. Disable via `setEnabled(false)`.

**AgentSandbox.execute()** — Must use a registered agent principal ID, NOT the tool name. Tool names are not registered in `ScopedPermissionRegistry`. Use the constant `AgentLoop.SANDBOX_AGENT_ID = "agent_loop"`. Using tool names causes permission checks against unknown principals.

**RemoteModelRegistry serialization** — Uses a hand-rolled JSON parser (not Gson/Moshi). When adding new fields to `RemoteModel`, must update BOTH `serializeList()` (write) AND `parseObject()` (read). Missing either breaks round-trip persistence. Old stored JSON without the field will parse as `false`/`""` (safe default).

**CrashReportStore.recordManual()** — Always sets `stackDigest = ""`. Use `record(component, throwable, ...)` when a Throwable is available to preserve the 800-char stack digest.

**OrchestratorCrashReporter B-11** — Goal snippet truncation (20 chars) is included in the AIRI_PROOF log line only, not in the crash-store JSON payload (which uses `throwable.message` via `record()`). This is intentional: PII stays out of disk storage while logs still carry context.

## What was already done vs. needed (Wave 3/4 audit)

Already implemented before Wave 3/4 session (no changes needed):
- AP-12, AP-13, AP-14/15, AP-17, AP-18, AP-19, AP-21, AP-22
- AP-23 (Import chat history), AP-24 (Templates nav), AP-25 (About AIRI + Technical Details)
- AP-33 (SmartActionEngine UILearning Stage 0), B-03, AP-52
- B-08 (token refresh before Firebase delete) — already done inside AuthService.deleteAccount()

Implemented in Wave 3/4 session:
- AP-SS, AP-20, AP-28, AP-36, AP-51
- B-01, B-02, B-06, B-07, B-09, B-11

## AP-36 Battery receiver

`HardwareProfiler.invalidateCache()` was added to support the `batteryLowReceiver` in `AIRIApplication`. The receiver is registered in `onCreate()` and listens for `Intent.ACTION_BATTERY_LOW` (system threshold ~15-20%).

**Why:** `cachedProfile` is `@Volatile` so assignment is atomic. The receiver calls `invalidateCache()` which sets it to null; the next `profile()` call re-reads fresh battery/power-save state.
