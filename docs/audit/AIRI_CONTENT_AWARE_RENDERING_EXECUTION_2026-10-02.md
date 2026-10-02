# AIRI Content-Aware Response Rendering Execution

**Date:** 2026-10-02  
**Scope:** Chat message and assistant response presentation only.  
**Branches:** `cp-foundation` first, then synchronized into `main` after verification.

## Extracted implementation plan

The attached requirements called for:

- Inspect and reuse the existing chat message model, `ChatViewModel`, streaming state, Markdown renderer, rich-content policy, fullscreen viewer, and message actions.
- Classify assistant content before rendering.
- Support plain text, Markdown, fenced code, structured JSON/XML/YAML/log content, Markdown tables, long responses, streaming-incomplete content, and RTL/LTR direction.
- Preserve copy, share, TTS, export, fullscreen, feedback, streaming, session identity, attachments, and existing theme/navigation.
- Add real classifier tests and run unit tests, lint, and debug assemble.

## Implemented

### Content classification

Added `AssistantContentClassifier` with explicit results:

- `PLAIN_TEXT`
- `MARKDOWN`
- `CODE_BLOCK`
- `STRUCTURED_CODE`
- `TABLE`
- `RICH_CONTENT`
- `MIXED_DIRECTION`
- `RTL_TEXT` / `LTR_TEXT`
- `STREAMING_CONTENT`
- `EMPTY_STATE`

Classification is pure, deterministic, and streaming-aware. An incomplete fenced block is not permanently treated as final prose.

### Content-aware rendering

Added `ContentAwareMessageRenderer` and wired it into the existing `AiBubble` and `AiStreamingBubble`.

It reuses:

- `BidiAwareMarkdownRenderer` for prose/Markdown and BiDi isolation.
- Existing Markdown parsing and code styling for fallback and mixed prose.
- Existing fullscreen viewer and action toolbar.

It adds specialized surfaces for:

- Fenced code-only responses with language label, monospace content, horizontal scrolling, and copy-code action.
- Structured JSON/XML/YAML/log-like responses through a code presentation path.
- Markdown tables with readable cells and horizontal scrolling.

No new message model, state owner, Agent architecture, database schema, provider path, or connector path was introduced.

### Fullscreen policy

`ChatRichContentPolicy` now uses the classifier and opens the existing fullscreen surface for:

- Long content.
- Fenced code.
- Structured content.
- Markdown tables.

## Verification

Targeted tests after the first compile fix:

```text
BUILD SUCCESSFUL
AssistantContentClassifierTest
ChatPresentationPolicyTest
ChatRichContentPolicyTest
```

Full verification:

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL
102 actionable tasks: 61 executed, 41 up-to-date
```

Native APK check:

```text
AIRI_VERIFY_NATIVE: debug APK contains lib/x86_64/libairi_native.so
```

Debug APK SHA-256:

```text
e848d4ad5a2505fd784404148a15fe9f5e97d064f54cb94d4b6fbdcd5ae55f11
```

## Explicit limits

- The classifier and renderer are covered by JVM tests and compile/lint/build verification.
- A full physical-device visual/performance run on HONOR X6c was not available in the sandbox, so frame timing and device-specific visual smoothness remain runtime acceptance items.
- Existing fullscreen, share, TTS, export, feedback, and session behaviors were preserved rather than replaced; this change does not claim a new regenerate implementation where one was not part of the existing `AiBubble` API.
