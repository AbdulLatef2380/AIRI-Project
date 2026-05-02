# AIRI — On-Device Android AI Runtime

## What This Is
AIRI is a production-grade on-device Android AI assistant built with:
- **Kotlin / Jetpack Compose** — UI and coroutine-based inference lifecycle
- **llama.cpp (JNI/C++)** — native LLM inference engine via `LlamaBridge.cpp`
- **Room** — persistent chat history / memory system
- **NDK 25.2.9519653 + CMake 3.22.1** — native build toolchain

This is a **native Android application**. It cannot run as a web server inside Replit.
The canonical build path is GitHub Actions CI (see `.github/workflows/android_build.yml`).
A pre-built debug APK is included at `airi-debug.apk` in the repo root.

---

## Build Requirements
| Tool | Version |
|------|---------|
| JDK | 17 (Temurin) |
| Android SDK | API 34 |
| NDK | 25.2.9519653 |
| CMake | 3.22.1 |
| Build tools | 34.0.0 |
| Gradle | wrapper (`./gradlew`) |

### To build locally (requires Android SDK):
```bash
./gradlew clean assembleDebug \
  -Pandroid.native.buildOutput=verbose \
  --stacktrace
```

### CI build (GitHub Actions):
Push to any branch — `.github/workflows/android_build.yml` runs automatically.
The signed APK artifact is uploaded at the end of the CI job.

---

## Key Source Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/airi/assistant/ai/LlamaManager.kt` | Core inference lifecycle state machine (1867 lines) |
| `app/src/main/cpp/LlamaBridge.cpp` | JNI bridge to llama.cpp (2539 lines) |
| `app/src/main/java/com/airi/assistant/ai/LlamaNative.kt` | JNI declarations |
| `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt` | Main UI ViewModel |
| `app/build.gradle.kts` | Android / CMake / NDK build config |
| `app/src/main/cpp/CMakeLists.txt` | Native build config |

---

## Runtime Stability Architecture

### Three-Layer Serialization
1. **`llamaDispatcher`** — `Dispatchers.IO.limitedParallelism(1)`: single-threaded coroutine dispatcher; no two JNI calls can overlap.
2. **`lifecycleLock` (Kotlin `Mutex`)** — coroutine-level ownership; `generateStream` holds it for the entire lifecycle of one generation.
3. **`LLAMA_LOCK` (C++ `std::mutex`)** — guards the llama.cpp context, sampler, and KV cache at the JNI boundary.

### Session / Generation Ownership
- **`g_session_id`** (native atomic) — bumped by `beginSession()` (hard reset). Captured in Kotlin as `sessionIdAtStart` before every decode loop.
- **`g_generation_id`** (native atomic) — bumped at entry of every `airi_generate_next()` call. Captured in Kotlin as `genIdAtStart` before `generateNextTokens()`. Expected active value while generation N is live: `genIdAtStart + 1`.

Both IDs are validated at **both** Main-dispatch boundary sites (per-batch and tail-flush). A callback is dropped — never reaches the UI — if either ID has moved on.

### State Machine
```
IDLE → PREFLIGHT → PREFILL → GENERATE → COMPLETE/ERROR/CANCELLED → CLEANUP → IDLE
```

### Cancel Paths
- **User cancel**: `cancelStream()` → sets `cancelRequested = true` + calls `LlamaNative.cancel()` + `LlamaNative.nativeCancel()`
- **Watchdog timeout**: sets `cancelRequested = true` + calls both cancel routes (same atomic, dual-call for future-proofing)
- **Context overflow** (status -3): `fullReset()` + retry once with trimmed history
- **Decode error** (status -1): `fullReset()` + surface error to UI
- **Cancelled** (status -2): `invalidateSession()` only (no full reset needed)

### Context Budget
- `MAX_HISTORY_TOKENS = 750` (token budget for history replay)
- `maxHistory = 4` messages (count cap)
- `trimContext()` walks backwards in user/assistant pairs, budgets against live `n_ctx` minus 512-token reserve
- `trimHistory()` is called after every completed generation to keep `chatHistory` within budget

---

## Known Gaps / Future Work
- Sampling parameters (`temperature`, `topK`, `topP`, `repeatPenalty`) are passed by Kotlin to `generateStream()` but the native sampler in `airi_generate_next()` uses hardcoded defaults (top_k=40, top_p=0.9, temp=0.7). Adding a `nativeSetSamplingParams()` JNI call would close this gap.
- `RuntimeSupervisor` (thermal / memory pressure monitoring) is not yet implemented.
- Draft model speculative decoding path (`SpeculativeManager`) is wired but requires a companion draft GGUF to activate.
