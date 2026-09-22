# AIRI Stability Audit and Release Verification

**Date:** 2026-09-22  
**Repository:** `AbdulLatef2380/AIRI-Project`  
**Scope:** Chat UI, local GGUF loading, JNI lifecycle, cloud streaming, model import, release diagnostics, and build verification.

## Executive Summary

The repository was audited before modification. The main stability risks were not one isolated defect: the local model path combined an artificial 100 MiB gate with weak GGUF preflight and a destructive import copy operation; the cloud path could retry or fail over after already emitting response text; Gemini placed the API key in the request URL; and local coroutine cancellation was not connected to native generation cancellation. These paths were corrected and the project passed the JVM unit-test, lint, debug APK, release APK, and AAB gates.

The verification proves source-level correctness, deterministic JVM behavior, native library packaging, and release symbol generation. It does **not** claim a 100% real-device guarantee: no physical Android device, real GGUF model, or live provider API key was available in this sandbox. Those remaining tests are listed explicitly in the final section.

## Implemented Fixes

### Local models and model import

The 100 MiB minimum was removed from `ModelValidator`, `FileUtils`, `ModelDownloadService`, and the JNI model-loading preflight. GGUF v2 and v3 headers are now checked before a model is accepted by Kotlin. The native loader remains the final authority and still rejects malformed GGUF through `is_valid_gguf`. This allows legitimate small models, including embedding models, while no longer treating a large file beginning with `GGUF` as valid by size alone.

Model imports now write to a temporary `.part` file, verify the copied length, and publish by rename only after the copy is complete. If the target filename already exists, a unique destination is selected. This prevents truncating an active mmap-backed model, which could otherwise produce an uncaught native `SIGBUS` while llama.cpp still reads the old mapping.

### Cloud streaming

OpenAI-compatible streaming now requires the `[DONE]` terminal event. EOF before that event is reported as a connection failure rather than a successful response. Gemini now sends the key through the `x-goog-api-key` header instead of placing it in the URL, reducing exposure in proxy, HTTP, and diagnostics logs. Gemini also rejects an empty stream without content.

`CloudBackend` now tracks whether a provider has emitted any token. Once partial content has been delivered, the request is not retried or failed over to another provider, preventing duplicated or mixed answers in the same response bubble. The user receives an explicit partial-response error and can retry manually.

### Local cancellation and release diagnostics

`LocalLlamaBackend` registers cancellation of the caller coroutine with `LlamaManager.cancelStream()`. This connects Compose/agent cancellation to the native producer and prevents abandoned collectors from allowing generation to continue indefinitely.

Native linking no longer uses `--strip-all`. Release builds now request `debugSymbolLevel = "FULL"`, and the generated symbol archive contains `arm64-v8a/libairi_native.so.dbg`. This makes future native tombstones symbolicateable against the exact release artifact.

### Chat UI

User bubbles were widened from 320dp to 360dp to better match the supplied reference image while retaining edge insets. Assistant bubbles are constrained to the same readable maximum instead of expanding across the entire row, preserving a clear visual distinction between user turns and model responses.

## Verification Results

| Gate | Result | Evidence |
|---|---:|---|
| Existing baseline build before fixes | PASS | `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` completed successfully |
| Regression test for supported small GGUF header | PASS | `ModelValidatorTest.acceptsSmallGgufHeaderWithSupportedVersion` |
| Regression test for unsupported GGUF version | PASS | `ModelValidatorTest.rejectsUnsupportedGgufVersionBeforeNativeLoad` |
| JVM unit tests after fixes | PASS | 32 tests completed successfully |
| Debug lint | PASS | `app/build/reports/lint-results-debug.html` |
| Debug APK | PASS | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | PASS | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release AAB | PASS | `app/build/outputs/bundle/release/app-release.aab` |
| Native library verification | PASS | Release APK contains `lib/arm64-v8a/libairi_native.so` |
| Native symbol archive | PASS | `app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip` contains `arm64-v8a/libairi_native.so.dbg` |
| Core project checks | PASS | `tools/verify_core_changes.py`: 41/41 |
| Security scan | PASS | `tools/security_scan.py`: no secret findings |
| Core health scan | PASS | No unfinished markers; resource parity passed |
| Diff hygiene | PASS | `git diff --check` passed |
| Static regression scan | PASS | No remaining 100 MiB gates in the reviewed paths; no Gemini `key=` URL construction |

## Independent Reference Review

The implementation was compared against official and public references, including [llama.cpp Android documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md), the official [llama.android example](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android), the [GGUF specification](https://github.com/ggml-org/ggml/blob/master/docs/gguf.md), [Android JNI guidance](https://developer.android.com/training/articles/perf-jni), [OpenAI streaming guidance](https://developers.openai.com/api/docs/guides/streaming-responses), and [Gemini streaming documentation](https://ai.google.dev/gemini-api/docs/streaming).

The comparison identified additional risks that were not silently presented as solved: AIRI uses a pruned, CPU/NEON-oriented native build; native model weights may remain mmap-backed after a logical unload until a later model load; the SSE parsers are still lightweight string parsers rather than a fully typed event parser; and the local token counter is approximate because native prompt/completion counts are not exposed through the current backend contract.

## Remaining Device-Level Release Tests

A physical or emulated Android test is still required before public release to prove native behavior rather than only packaging:

1. Load a real, verified Llama/Qwen/Gemma GGUF from SAF storage, generate Arabic and English prompts, then unload and reload a second model repeatedly.
2. Repeat start → cancel → start → model swap at least 100 times under rotation and background/foreground transitions, watching logcat for native faults and stale callbacks.
3. Measure RSS and `/proc/<pid>/maps` before and after chat, embedding, and projector unloads on a low-RAM device.
4. Exercise live OpenAI and Gemini keys with a deterministic SSE proxy or MockWebServer, including `[DONE]`, early EOF, 429, 500, timeout, cancellation, Unicode, and role-only chunks.
5. Verify `ApplicationExitInfo.REASON_CRASH_NATIVE` and Crashlytics/NDK symbolication using the generated symbol archive for the exact version and ABI.

These are environment-dependent acceptance tests, not reasons to hide the successful source and build verification already completed.

## Changed Files

- `app/build.gradle.kts`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/LlamaBridge.cpp`
- `app/src/main/java/com/airi/assistant/ai/ModelValidator.kt`
- `app/src/main/java/com/airi/assistant/execution/backend/CloudBackend.kt`
- `app/src/main/java/com/airi/assistant/execution/backend/LocalLlamaBackend.kt`
- `app/src/main/java/com/airi/assistant/execution/cloud/GeminiAdapter.kt`
- `app/src/main/java/com/airi/assistant/execution/cloud/OpenAIAdapter.kt`
- `app/src/main/java/com/airi/assistant/tools/FileUtils.kt`
- `app/src/main/java/com/airi/assistant/tools/ModelDownloadService.kt`
- `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt`
- `app/src/test/java/com/airi/assistant/ai/ModelValidatorTest.kt`
