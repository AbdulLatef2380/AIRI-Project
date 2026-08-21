# Gate — Text and Image Intelligence

## Implemented Evidence

| Concern | Classification | Evidence |
|---|---|---|
| Text context budgeting contract | `BUILD_VERIFIED` | Shared `TextContextPolicy` tests derive attachment capacity from model, output, system, conversation, memory, and existing attachment allocations |
| Structural text chunks | `BUILD_VERIFIED` | Shared chunk tests preserve offsets and readable boundaries for mixed Arabic/English text and code |
| Bounded Android text read | `BUILD_VERIFIED` | Android unit regression passed after replacement of full-file `readText()` with a bounded streaming read |
| No-vision image routing | `BUILD_VERIFIED` | Android unit regression passed after image requests without verified vision are rejected rather than sent as filename markers |
| Image decode source limits | `BUILD_VERIFIED` | Android unit regression passed after shared decoded-size/dimension policy is checked before bitmap allocation |
| Attachment static guard | `SOURCE_VERIFIED` | `scripts/airi_attachment_security_scan.py` emits machine-readable `SOURCE_VERIFIED` for source guards only |
| Image vision inference | `EXTERNAL_VERIFICATION_REQUIRED` | Requires a loaded compatible vision model, projector, real image input, and observed payload/result path |
| Text retrieval/RAG adapter | `PLANNED` | Shared budget and chunk contracts exist; indexed storage and query ranking are not connected yet |
| PDF extraction | `PLANNED` | No real PDF text extractor is connected; PDF must not be advertised as text-supported |
| Multi-image vision payload | `PLANNED` | Current Android native path accepts a first primary image only; it is not multi-image support |

## Routing Rule

An image request is sent only when the selected model reports verified vision capability and its native projector is loaded. Otherwise AIRI presents an explicit rejection, retains no fabricated vision result, and never treats filename metadata as image analysis. Text-only attachments remain labeled `BEGIN/END UNTRUSTED TEXT ATTACHMENT` and are bounded before content enters memory.

## Local Verification

```bash
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx768m' \
  :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  '-Pkotlin.compiler.execution.strategy=in-process' \
  :app:testDebugUnitTest

python3 scripts/airi_attachment_security_scan.py
```

The static guard is intentionally not a runtime claim. It detects regression-prone source patterns only. Runtime vision, image selection, provider payload delivery, and real Windows/Linux drag/drop need their own target-device evidence.
