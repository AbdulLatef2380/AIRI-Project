# AIRI Runtime Simulation and Break-Test Report

**Date:** 2026-09-22. **Branches:** `main`, `cp-foundation`. **Purpose:** provide a repeatable, silent pre-device gate for local model lifecycle, cloud streaming contracts, and the redesigned chat surface.

## Scope and honest boundary

The simulation is deterministic and dependency-free. It does not pretend to load llama.cpp native code, access Android Storage Access Framework, contact a live provider, or prove behavior with a real API key. Those facts require the Android emulator or a physical device and, for cloud acceptance, a test credential. The simulation instead attacks the contracts surrounding those operations so regressions are caught in CI before device testing.

## Scenarios executed

| Area | Break test | Result |
|---|---|---:|
| Local model preflight | Accept a small valid GGUF v3 header; reject an unsupported version | PASS |
| Storage import | Import the same model twice without overwriting the first destination; verify no `.part` leak | PASS |
| Local lifecycle | Load, stream, cancel, unload, switch model, and repeat 100 cycles | PASS |
| Cloud OpenAI-compatible SSE | Assemble tokens and require `[DONE]`; reject early EOF | PASS |
| Cloud Gemini-shaped SSE | Assemble Arabic/Unicode candidates; reject an empty successful stream | PASS |
| Credential hygiene | Verify the Gemini URL contract contains no `key=` parameter | PASS |
| Chat UI | Verify user filled surface, assistant open reading surface, per-message direction, renderer, and thinking dots | PASS |

The exact command is:

```bash
python3 tools/airi_runtime_simulation.py
```

The current output is:

```json
{
  "status": "PASS",
  "results": {
    "local": {"model_switch_cancel_cycles": 100, "local_tokens": 4},
    "cloud": {"openai_tokens": 2, "gemini_tokens": 2, "early_eof_rejections": 1, "empty_stream_rejections": 1},
    "ui": {"assistant_open_surface": 1, "user_filled_surface": 1, "direction_contract": 1}
  }
}
```

## Device acceptance still required

A successful simulation is not a substitute for hardware. The release acceptance run must still import a real verified GGUF through the Android picker, generate Arabic and English text, cancel and restart generation, swap models while navigating between conversations, background and foreground the app, and inspect logcat for native `SIGBUS`, `SIGSEGV`, stale callbacks, or memory growth. The same run should exercise a test OpenAI-compatible key and a Gemini key with long prompts, Unicode, 429/500 responses, timeout, cancellation, and app restart. Finally, the signed release's native symbols must be used to symbolicate any native crash.

## CI integration

The Android workflow runs this simulation before the Android build. Therefore every push to `main` and `cp-foundation` receives the same deterministic contract gate in addition to JVM tests, lint, APK packaging, native-library verification, and emulator instrumentation.
