# AIRI Chat UI Redesign Verification

**Date:** 2026-09-22  
**Scope:** Message bubbles, assistant response layout, RTL/LTR behavior, thinking state, and responsive Compose behavior.

## Design Decision

The supplied reference image uses a filled outgoing user bubble and an assistant response surface that is visually quieter. AIRI previously rendered both user and assistant content as bordered filled rectangles. That was the primary design defect. The redesign keeps a filled, right-side user bubble and changes the assistant response into an open reading column with only the AIRI identity mark and lightweight actions. The assistant response is no longer enclosed by a bubble background or border.

This is consistent with the authoritative principles reviewed, while avoiding an unsupported claim that Gemini or ChatGPT publish fixed bubble dimensions. Material 3 and Jetpack Compose document color roles, typography, responsive layout, accessibility, directionality, and progress semantics; they do not prescribe a universal chat-bubble width or a single vendor-specific assistant bubble style.

## Implemented Changes

### User messages

User messages remain visually distinct through a filled branded surface, right-side placement, and responsive width constraints. The bubble wraps naturally and does not use a fixed height. Text uses `TextAlign.Start` and a per-message layout direction, so Arabic paragraphs begin at the right while English and Chinese paragraphs remain left-to-right. Images and voice attachments remain inside the user message container.

### AIRI responses

Assistant messages now use an open reading surface. The avatar remains at the assistant leading edge, while the response text occupies a readable, responsive column up to 680dp. The former `AiBubbleSurface` background, border, and rounded rectangle were removed from the message body. This prevents the long assistant response from looking like another user bubble and improves reading rhythm for code, Markdown, Arabic, English, and Chinese.

Actions remain below the response rather than competing with the text: speak, copy, feedback, trace, and execution-origin details are secondary controls. The response remains selectable through the existing `SelectionContainer` behavior.

### Language direction

A paragraph-direction helper detects Arabic/Hebrew script and uses RTL; English, Chinese, and code default to LTR. This is deliberately separate from the screen's overall layout direction. The message text is wrapped in `CompositionLocalProvider(LocalLayoutDirection ...)`, and alignment uses `TextAlign.Start` rather than absolute left/right alignment.

This follows Android's guidance to use start/end semantics and test mixed Arabic, numbers, links, email addresses, and code. A future refinement can pass Compose's explicit `TextDirection.ContentOrLtr` or `ContentOrRtl` directly into the Markdown renderer if its API is extended; the current change is safe for the existing Markdown component and preserves code/URL content.

### Thinking state

Thinking is now treated as a transient state, not as a fake assistant message. The large status card and rotating stage text were removed from the main chat presentation. AIRI shows three small animated dots beside the assistant identity before the first token and while the stream is in a thinking stage. The dots have an accessibility description, stop when the response starts or ends, and do not claim a percentage or a fabricated remaining time.

This follows the official distinction between indeterminate loading and determinate progress. Model thinking has no reliable completion percentage, so a determinate bar would be misleading. The indicator is placed at the assistant response origin, below the composer overlay and aligned with the assistant avatar, rather than floating in the center of the screen or inside a message bubble.

## Official Guidance Reviewed

| Topic | Applied principle | Source |
|---|---|---|
| Material 3 system | Use Material color, typography, shape, and accessibility roles rather than arbitrary one-off styling | [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) |
| Typography | Use body roles for reading-heavy message text and let long content reflow | [Material typography](https://m3.material.io/styles/typography/applying-type) |
| Contrast | Preserve at least 4.5:1 for small text and 3:1 for large text/graphics | [Material color contrast](https://m3.material.io/foundations/designing/color-contrast) |
| RTL | Use start/end, mirror directional controls only, and test mixed-direction strings | [Material bidirectionality](https://m3.material.io/foundations/layout/bidirectionality-rtl) |
| Android Bidi | Keep URLs, code, and machine-readable values stable; isolate mixed-direction user text when composing translated strings | [Android language support](https://developer.android.com/training/basics/supporting-devices/languages) |
| Compose text direction | Content-based direction and explicit LTR/RTL options exist for text paragraphs | [Compose TextDirection](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/style/TextDirection) |
| Compose accessibility | Add semantics for controls and meaningful state changes; avoid announcing every streaming token | [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) |
| Loading | Use indeterminate loading when completion cannot be measured; do not invent progress | [Compose progress indicators](https://developer.android.com/develop/ui/compose/components/progress) |
| Loading timing | Avoid a visible indicator for very short waits; use a visible/cancellable state for long operations | [Material progress guidelines](https://m3.material.io/components/progress-indicators/guidelines) |
| Official Compose chat sample | Keep the composer at the bottom, respond to IME/navigation bars, and use stable message keys | [Jetchat Conversation](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt) |
| Official Compose input sample | Use a clear send action, IME Send, and do not submit blank text | [Jetchat UserInput](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/UserInput.kt) |

## Verification

The following gate passed after the redesign:

- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:assembleDebug`
- Native library verification inside the debug APK
- `git diff --check`

The build completed successfully with the redesigned `ChatScreen.kt`. No physical device or screenshot-rendering emulator was available in this sandbox; device acceptance should still cover Arabic/English/Chinese, mixed links and code, font scaling, TalkBack, dark/light themes, and small screens.

## Acceptance Checklist

A reviewer should confirm that user text appears in a filled outgoing bubble, assistant text has no enclosing message rectangle, Arabic begins on the correct side, English and Chinese remain readable left-to-right, code and URLs are not reversed, the thinking dots appear only during generation, the dots disappear on the first token, and the composer remains accessible above the IME.
