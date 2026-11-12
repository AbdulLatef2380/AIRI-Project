# AIRI native provenance

**Generated/reviewed:** 2026-11-12

## Component identity

| Field | Value |
|---|---|
| Component | llama.cpp + ggml vendored C/C++ source, built through Android CMake/JNI |
| AIRI upstream URL | https://github.com/ggml-org/llama.cpp |
| Upstream license status | Official repository declares MIT; downstream vendored tree must preserve upstream notices before distribution |
| Local native snapshot commit | `5e3c51c89333912e45eab0f0d57b7897f9096a53` (last commit touching the native snapshot) |
| Local tree SHA-256 | `40aa7d5d854c64b7f278df35e961a0fde40cd291e7fd316d79f51c2a1f62d120` |
| File count | 276 |
| Closest confirmed upstream commit | **NOT_ESTABLISHED** — the local Git history contains ZIP/app imports and no recorded upstream commit or submodule pointer. It would be unsafe to invent an upstream SHA. |
| Import evidence | Local history starts with app archive imports on 2026-04-20 through 2026-04-30; see `git log -- app/src/main/cpp/llama`. |

The machine-readable file is [`NATIVE_SNAPSHOT_MANIFEST.json`](NATIVE_SNAPSHOT_MANIFEST.json). It contains per-file SHA-256 values, byte sizes, native build configuration, ABI targets, and the last local commit that changed the snapshot.

## Build and pruning

`app/src/main/cpp/CMakeLists.txt` documents the current hard-pruned build:

- compiled model families: LLaMA/LLaMA4, Qwen text/vision, and Gemma text/vision;
- MTMD is vision-only and excludes the audio pipeline through `MTMD_DISABLE_AUDIO`;
- Android ARM64 uses NEON; debug x86_64 is scalar fallback;
- compile and link pools are capped for CI memory safety;
- `--gc-sections` is enabled; `--strip-all` is intentionally not used because release symbolication must remain possible;
- the native version markers are `GGML_VERSION="airi-b5399"` and `GGML_COMMIT="airi"`.

The keep-list is enforced by source lists and dispatcher pruning. Reintroducing an architecture requires changing the source list and dispatcher together, followed by an equivalence/build test.

## Vendor decision

**Decision: retain vendored source for now.** The Android build depends on a fixed, aggressively pruned source set and AIRI-specific JNI/CMake patches. Switching to a submodule, subtree, package, or build-time fetch would change the resolved source graph and could re-enable architectures or alter ABI behavior. No equivalence test currently proves such a migration safe.

The decision must be revisited only after a candidate upstream snapshot is identified and an equivalence test proves: supported GGUF load, text inference, vision path, JNI ABI, ARM64 release build, debug x86_64 build, and memory-bounded CI compilation.

## Reproduction

```bash
python3 tools/native_snapshot_manifest.py
sha256sum docs/native/NATIVE_SNAPSHOT_MANIFEST.json
```

The manifest itself is regenerated after native source changes. Its `commit` field intentionally points to the last native-touching commit rather than repository HEAD, avoiding a self-referential documentation change.
