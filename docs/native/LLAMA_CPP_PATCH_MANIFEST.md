# llama.cpp / ggml AIRI patch manifest

This manifest is intentionally conservative. Because the vendored snapshot was imported from app archives and does not retain an upstream commit mapping, entries below describe **observable AIRI deltas and build intent**, not fabricated patch IDs.

| Area | AIRI change retained | Reason | Verification state |
|---|---|---|---|
| CMake source selection | Hard-pruned model and MTMD source lists | Bound APK size, memory, compile time, and supported model surface | Source-reviewed; native build required |
| Android ABI | ARM64 NEON plus scalar x86_64 debug fallback | Target physical Android devices and emulator CI | CMake-reviewed; device/build verification required |
| CI compile pools | CMake job pools compile=2/link=1 | Prevent hosted-runner OOM during large C++ compilation | CI configuration reviewed |
| Build flags | `-O3`, fast math, section garbage collection | Performance and binary-size goals | Release performance/equivalence test required |
| Logging | `LOG_DISABLE_LOGS` and AIRI version markers | Prevent native log noise and identify the snapshot | Source-reviewed |
| Multimodal | Vision-only MTMD set; audio pipeline disabled | Audio headers/runtime are not part of this snapshot | Build and vision runtime verification required |
| Debug overlay | Release-safe `mtmd-debug.h` stub and debug-only instrumentation define | Clean release builds must not depend on developer-local headers | CI build evidence exists in repository history |
| JNI bridge | `LlamaBridge.cpp` builds `airi_native` | AIRI Kotlin/native integration | ABI/build verification required |

## Prohibited assumptions

- This file does not claim that the local tree is byte-for-byte equivalent to a particular upstream commit.
- It does not claim that every upstream llama.cpp license or third-party notice is present in the APK.
- It does not claim support for architectures physically removed by CMake pruning.
- It does not authorize shipping GGUF model files; each model requires its own source, hash, and license review.

## Required evidence for a future upstream migration

1. Record upstream URL, exact commit/tag, import date, and archive hash.
2. Produce a file-level diff and regenerated native snapshot manifest.
3. Preserve/refresh notices for llama.cpp, ggml, stb, miniaudio, and other vendored components actually included.
4. Run ARM64 release and x86_64 debug builds, JNI smoke tests, supported text/VLM fixtures, and memory-bounded CI.
5. Review the new SBOM and license report before merging.
