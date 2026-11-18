# Phase 3 — Runtime closure and measurable release gates

## Implemented

- Added `MemoryPressurePolicy`, a pure decision table for `onTrimMemory`.
  `RUNNING_CRITICAL` and `COMPLETE` may release native memory; `UI_HIDDEN`
  only prunes stale workspaces and never unloads a live model.
- Wired `AIRIApplication.onTrimMemory` to that policy and added JVM regression
  tests for low, critical, background, complete, and ignored levels.
- Added `launchGuarded` around deferred application initialization and integrity
  warm-up. Cancellation is rethrown; unexpected failures are reported through
  the existing crash reporter and cannot silently escape a supervisor.
- Made recovery's process-level uncaught-exception handler idempotent, so a
  repeated initialization cannot chain duplicate handlers.
- Made the local crash-report file update serialized and resilient to a failed
  atomic rename; the previous complete report is retained as a fallback.
- Hardened the native fatal-signal path. The signal handler now performs only a
  fixed `write(2)` marker, installs the default action, and re-raises the signal.
  Android tombstones remain responsible for the full native backtrace.
- Added a real Baseline Profile test module with a startup scenario, wired it
  to the Android app, and kept `profileinstaller` in the release runtime.
- Added `.github/workflows/android_phase3_performance.yml` with two gates:
  SDK-free contract evidence and an emulator-backed profile collection job.
- Added `tools/verify_phase3_runtime_contracts.py`; it explicitly reports that
  device performance is **not measured** when only the static gate runs.

## Verification performed in this environment

- `python3 tools/verify_phase3_runtime_contracts.py`: **PASS (10/10)**.
- Workflow/static wiring checks: **PASS**.
- `git diff --check`: **PASS**.
- Android Gradle/JVM/device tests were not claimed here because this sandbox
  does not expose an Android SDK. The GitHub workflow is the required proof for
  Gradle compilation, profile generation, and emulator execution.

## Release evidence required from CI

The performance workflow must finish successfully and upload:

1. `AIRI-Phase3-runtime-contracts`.
2. `AIRI-Phase3-startup-profile`, including the generated profile and reports.

A green SDK-free gate alone does not prove startup latency, native crash
symbolication, or profile installation. Those require the connected emulator
job and the release APK produced by CI.
