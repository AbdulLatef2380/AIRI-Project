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

## Sampling Parameters (SPEC v4 — fully wired)

All 7 per-generation sampling parameters are now end-to-end from UI → native:

| Layer | What was added |
|-------|---------------|
| `LlamaBridge.cpp` | 8 globals (`g_sp_temperature`, `g_sp_top_k`, `g_sp_top_p`, `g_sp_min_p`, `g_sp_repeat_penalty`, `g_sp_presence_penalty`, `g_sp_frequency_penalty`, `g_sp_penalty_last_n`). `airi_generate_next()` builds sampler chain from these instead of hardcoded constants. `nativeSetSamplingParams()` JNI entry point writes them under `LLAMA_LOCK`. |
| `LlamaNative.kt` | `external fun nativeSetSamplingParams(temperature, topK, topP, minP, repeatPenalty, presencePenalty, frequencyPenalty)` declaration. |
| `LlamaManager.kt` | `runCatching { LlamaNative.nativeSetSamplingParams(...) }` called immediately before every `generateNextTokens` / `generateNextTokensSpeculative` invocation, inside `lifecycleLock` on the single-threaded `llamaDispatcher`. |

Sampler chain order: `penalties → top_k → top_p → min_p → temperature → dist`.  
Each stage is skipped if its parameter is at its disabled value (e.g. `top_k=0`, `min_p=0.0`).  
`llama_sampler_init_penalties` is called with the header-confirmed 4-arg signature: `(penalty_last_n=64, repeat, freq, present)`.

## Known Gaps / Future Work
- `RuntimeSupervisor` (thermal / memory pressure monitoring) is not yet implemented.
- Draft model speculative decoding path (`SpeculativeManager`) is wired but requires a companion draft GGUF to activate.
- `g_sp_penalty_last_n` (repeat-penalty window) is fixed at 64 and not yet exposed via the Generation Settings dialog. Add a `jint penaltyLastN` param to `nativeSetSamplingParams` when needed.
