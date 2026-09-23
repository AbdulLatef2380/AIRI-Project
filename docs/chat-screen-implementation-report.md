# AIRI Chat Screen — Implementation Report

## Scope and baseline

The implementation was based on `origin/cp-foundation` at commit `57ce24302a4b4f0f05cf8b04c97117a6e0d849b6`, after comparing it with `origin/main` at `b7a506390a6d7d6fcf91674c87aa552dd4940860`. The working branch is `chore/chat-screen-production`. Existing runtime ownership was preserved: `ChatViewModel` remains the screen-state owner, `ExecutionStatusBus` remains the execution-status source, `RuntimeRouter` remains the runtime selector, and existing attachment, plan, activity, cancellation, and persistence paths remain in use.

## Files changed

| File | Reason | Important points |
|---|---|---|
| `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt` | Apply the production chat-screen corrections in the existing screen | Responsive user message width, readable assistant width, AIRI top-bar identity, human-readable model label, logical composer direction, stronger placeholder contrast, and 48dp composer touch targets. |
| `app/src/main/java/com/airi/assistant/ui/screens/ChatPresentationPolicy.kt` | Centralize small, pure presentation decisions without creating a second runtime or message state | Delegates text direction to the existing `LanguageRuntimeManager`; classifies message length and resolves human model labels. |
| `app/src/test/java/com/airi/assistant/ui/screens/ChatPresentationPolicyTest.kt` | Add deterministic unit coverage for presentation behavior | Arabic/English direction, mixed content, URLs, message-length boundaries, bubble-width constraint, and provider-label sanitization. |
| `docs/chat-screen-implementation-report.md` | Record the implementation and verification status | This report. |

## UI changes

The user message no longer uses a fixed 360dp width. Short and medium messages use content width, while long messages are constrained to a responsive fraction of the available chat width and a 640dp maximum. The neutral theme surface remains the bubble background; no bright blue user bubble was introduced.

Assistant content now has an 800dp maximum readable width instead of the previous narrow 360dp cap. Existing `BidiAwareMarkdownRenderer` and `LanguageRuntimeManager` remain responsible for mixed-language and rich-text handling; no duplicate Bidi engine was introduced.

The top bar now presents `AIRI` as the product identity. Internal provider/model identifiers are mapped to human-facing labels such as `Gemini Flash`, `Local Llama`, `Claude`, `OpenAI`, `Local`, `Cloud`, or `Auto`. The subscription badge was removed from the normal chat header so provider errors and ordinary chat do not look like a paywall.

The composer no longer forces `LayoutDirection.Ltr`. Its main attachment, microphone, and send/stop controls use 48dp visual/touch regions, and the placeholder uses `onSurfaceVariant` with stronger contrast. Existing real callbacks remain wired to `ChatViewModel.cancelGeneration`, `sendMessageWithAttachments`, and the current voice/attachment handlers.

## Runtime connections

Execution UI remains connected to the existing `AgentPlanViewModel`, `AgentPlanOverlay`, and `ActivityFeedComposable`. `ChatViewModel` continues to collect `ExecutionStatusBus.status`; the stop action continues to call `ChatViewModel.cancelGeneration()` rather than merely hiding a spinner. Attachment dispatch continues through the existing draft and capability policy paths, including `currentCapabilityDescriptor()` and `sendMessageWithAttachments()`.

No new chat runtime, global event bus, execution engine, cancellation system, model router, attachment engine, or independent message state was added.

## Tests and review performed

The added unit tests cover Arabic, English, mixed content, URLs, short/medium/long/very-long thresholds, responsive bubble constraints, and raw provider-ID label mapping. Static validation completed with `git diff --check`. A second adversarial scan found no changed-screen occurrences of `LayoutDirection.Ltr`, `paddingLeft`, `paddingRight`, the former 360dp assistant cap, raw provider error strings, or sensitive literals in the new files. The runtime linkage was rechecked by searching the actual screen and ViewModel call sites.

The requested external skill search was also performed. The strongest relevant result was `thebushidocollective/han@android-jetpack-compose` with approximately 2.2K installs: <https://skills.sh/thebushidocollective/han/android-jetpack-compose>. No external skill was installed because the repository already contains the required Compose/runtime architecture and installing a skill would not substitute for project-specific verification.

## Build status and blocker

The command attempted was:

```text
./gradlew :app:compileDebugKotlin --no-daemon
```

It did not reach Kotlin compilation because the environment has no Android SDK configured. Gradle reported:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in local.properties.
```

`ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, and the standard SDK locations checked in the sandbox were absent. Consequently, `assembleDebug`, `lintDebug`, instrumented UI tests, and device previews could not be truthfully reported as passed. The remaining blocker is environmental Android SDK availability, not a reported compiler result.

## Remaining blockers

The actual Android build and device-level interaction matrix still require an Android SDK/device or emulator. In particular, TalkBack, keyboard/IME behavior, orientation recreation, compact/medium/expanded window classes, long streaming conversations, and full UI interaction flows must be executed after the SDK is provisioned. No claim of runtime success is made for those blocked checks.

## Second review and compatibility pass

The specification was re-read in full and the implementation was checked again against its state matrix and accessibility requirements. The empty state now renders the existing AIRI foreground asset with the already-defined low-cost pulse animation; previously the animation values were calculated without rendering the identity mark. Response actions, feedback actions, attachment removal, input expansion, full-screen input, and scroll-to-latest now use 48dp touch regions where they are interactive, and previously missing action descriptions were added using localized resources. The message-list pinned-state calculation now includes `firstVisibleItemScrollOffset`, preventing false “at bottom” detection while the user is reading inside a large message.

Compose previews were added for dark empty chat, light messages, Arabic RTL, English LTR, mixed response text, long code, streaming, empty composer, and generating composer at compact, medium, and expanded-like widths. Resource XML was parsed successfully with Python's standard XML parser, and `git diff --check` remains clean.

The required app verification tasks were attempted again after these changes:

```text
:app:testDebugUnitTest  -> blocked during project configuration: Android SDK not found
:app:lintDebug          -> blocked during project configuration: Android SDK not found
:app:assembleDebug      -> blocked during project configuration: Android SDK not found
:core-domain:test       -> also blocked because the root project configures :app and requires Android SDK
```

The exact blocker remains the missing `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties` `sdk.dir`. No device/emulator, screenshot test, TalkBack test, IME test, orientation test, or runtime UI test can be honestly marked passed until the SDK is provisioned. The verification therefore distinguishes completed static/resource checks from blocked Android execution checks.
