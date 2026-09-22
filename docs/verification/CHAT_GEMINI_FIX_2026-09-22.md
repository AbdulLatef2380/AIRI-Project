# AIRI Chat and Gemini Fix Verification

**Date:** 2026-09-22

## Fixes

- `GeminiAdapter` now sends `ExecutionRequest.imageParts` as Gemini REST `inline_data` parts with MIME type and base64 data. It does not use OpenAI `image_url`; the API key remains in `x-goog-api-key`, not the URL.
- `ResponseOptimizer` no longer returns random English canned replies for Arabic. Arabic uses Arabic shortcuts; CJK and unsupported languages reach the actual model.
- User messages now use `AiriTheme.surfaceVariant` and `AiriTheme.onSurface`, adapt to light/dark/AMOLED themes, and use the compact reference placement.
- AIRI prose remains an open background surface. Code, structured traces, and attachments can use inner copyable surfaces. Actions are speaker, more, copy, dislike, and like with 38dp touch targets.

## Verification

| Gate | Result |
|---|---|
| `:app:compileDebugKotlin` | PASS |
| `:app:testDebugUnitTest` | PASS |
| Runtime simulation | PASS; 100 local model switch/cancel cycles |
| Gemini multimodal contract | PASS; `inline_data` present, `image_url` absent |
| OpenAI/Gemini SSE contracts | PASS |
| UI direction/open assistant surface contract | PASS |
| Security/static checks | PASS; 88/88 |
| `git diff --check` | PASS |

The deterministic simulation proves local source/runtime contracts. It does not claim a live provider call or physical device test without credentials and hardware.

## References

- [Gemini image input](https://ai.google.dev/gemini-api/docs/image-understanding)
- [Android Jetchat](https://github.com/android/compose-samples/tree/main/Jetchat)
- [Stream Chat Android AI](https://github.com/GetStream/stream-chat-android-ai)
- [llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
- [Material 3 loading indicators](https://m3.material.io/components/loading-indicator/overview)

## Reproduction

```bash
python3 tools/airi_runtime_simulation.py
python3 tools/security_scan.py
python3 tools/verify_core_changes.py
./gradlew --no-daemon --max-workers=1 :app:compileDebugKotlin :app:testDebugUnitTest --console=plain
```

**Status:** local correction gates passed; ready for selective transfer to `main` and `cp-foundation`.

Expected Gemini image shape:

```json
{"contents":[{"role":"user","parts":[{"text":"ما في الصورة؟"},{"inline_data":{"mime_type":"image/jpeg","data":"<base64>"}}]}]}
```

*No credentials or image bytes are stored in this report.*

**Final status: PASS.**

**End of report.**
