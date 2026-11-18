# Phase 2 — Runtime stability and native performance closure

## Scope

This phase closes the native/runtime items from the performance plan without introducing the Phase 3 crash handler, signal interception, Baseline Profile, or CI benchmark gates.

## Implemented

- Native KV/context/projector telemetry no longer takes the llama or MTMD mutex. The UI-facing JNI getters read release/acquire snapshots published by the owner of the native state. Decode, load, reset, trim, runtime-mode changes, and projector load/unload publish the relevant state.
- ARM dot-product and i8mm remain runtime-dispatched through ggml's `getauxval(AT_HWCAP/AT_HWCAP2)` checks. No unsafe global `-march` was added, so devices without those instructions continue to use the safe backend.
- Model downloads use a typed `dataSync` foreground service, reject duplicate workers, return `START_NOT_STICKY`, handle missing app storage, handle Android foreground timeout, and preserve cancellation semantics.
- Microphone services check `RECORD_AUDIO` before foreground promotion, use the declared microphone foreground-service type on Android Q+, catch service-start rejection, and avoid destroying an uninitialized voice manager.
- R8 keeps the concrete Gson/TypeToken persistence models used by AIRI and the JNA bridge used by Vosk, without restoring the old whole-application keep rule.
- `tools/verify_phase2_runtime_contracts.py` provides an SDK-free regression gate for the above contracts.

## Verification evidence

- `python3 tools/verify_core_changes.py`: passed (`CORE_STATUS=0`).
- `python3 tools/verify_phase2_runtime_contracts.py`: passed (`11/11`).
- ARM feature gate check: passed; ggml contains `HWCAP_ASIMDDP` and `HWCAP2_I8MM` runtime detection and the CMake target includes `cpu-feats.cpp`.
- `git diff --check`: passed.
- Gradle JVM tests were attempted but could not configure the Android project in this sandbox because Android SDK is absent: `SDK location not found`. This is an environment limitation, not a test failure; CI remains the required Android compilation/instrumentation proof.

## Explicitly deferred to Phase 3

Native fatal-signal handling, uncaught coroutine/worker exception boundaries, memory-pressure regression tests, Baseline Profile generation, and CI performance measurement are intentionally not mixed into this commit.
