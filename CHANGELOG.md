# AIRI — Changelog

## Unreleased — Report 5, batch 2

Static verification only (no Android SDK / Kotlin compiler available). Item covered: C3. **P3 (Room encryption) is deliberately not done** — see below.

- **C3 — custom-skill endpoints:** `CustomSkillSecurity.isValidEndpoint` now also rejects non-public hosts (`HostAddressPolicy.isBlockedHost`: private/loopback/link-local/internal names and any IP spelling). `CustomSkillExecutor` uses `HostAddressPolicy.PublicOnlyDns` (names that resolve to private addresses fail) and a network interceptor that re-checks every hop, so a redirect to an IP literal is refused too. A blocked address is reported as a network error and may be retried a few times before the skill fails.
- **P3 — not implemented:** encrypting `AiriDatabase` / `ExperienceStore` needs a new native dependency (SQLCipher), a passphrase held in the Keystore, and a one-time migration of existing plaintext databases (an interrupted or failed migration loses users' chats and memory). It cannot be verified without a build, so it needs a decision and a device test first.

## Unreleased — Report 5 (privacy, deletion, scheduled tasks), batch 1

Source: AIRI report 5. Static verification only: no Android SDK / Kotlin compiler was available, so nothing below was compiled or run. Items covered: P1, P2, P4, P5, A1. Not yet done: P3 (Room encryption), C3 (private-address check for custom-skill endpoints).

- **P1 — account deletion:** new `FirestoreAccountDataDeletion` replaces the "unavailable" default. The device files a request in `accountDeletionRequests`; the new `processAccountDeletionRequest` Cloud Function deletes `users/{uid}` and every subcollection with the Admin SDK and writes the verdict. "Deleted" is returned only on a server `completed`; a timeout or error is a failure and leaves the Firebase account and local data untouched for a retry. **Needs `firebase deploy --only functions,firestore:rules`.**
- **P2 — cloud memory:** the same function handles `kind: memory` (deletes `users/{uid}/memory/**` and `sync_meta/memory`); client API `ServiceLocator.cloudDataDeletion.deleteCloudMemory(uid)`. No screen calls it yet.
- **P4 — screen reading:** `read_screen` now needs per-read approval (`ScreenReadConsent`, wired to the chat confirmation card; refused when no UI is available). Password fields are skipped in `read_screen`, the execution engine's observe step and `ScreenContextHolder`. Not verified on a device whether Android returns text for password fields.
- **P5 — crash reports:** `FirebaseCrashReporter.setUserId` now sends `anon-<sha256 prefix>` instead of the raw uid; `clearUserData()` (blank id + `deleteUnsentReports`) runs on sign-out during account/local deletion. No caller of `setUserId` exists in this repository. `CrashPseudonymTest` added.
- **A1 — scheduled "confirm / skip":** `ScheduledApprovalPolicy` + `ScheduledAgentWorker`: under CONFIRM an automatic run whose target would change data (or cannot be classified) is held — outcome stays PENDING and the user gets "needs your approval"; "Run now" approves it. SKIP behaves as before. `ScheduledApprovalPolicyTest` added.

## Unreleased — Report 4 (voice, billing, API keys), batch 1

Source: AIRI report 4. Static verification only: no Android SDK / Kotlin compiler was available, so nothing below was compiled or run. Items covered: V1, V2, B1, B2, B3, B4, K1.

- **V1 — confirmation bound to the target element:** `AccessibilityExecutionEngine.actionApprover` asks for approval at the moment a consequential element is about to be clicked; without a UI gate the action is blocked (fail-closed). `AccessibilityPolicyGuard.requiresConfirmation` matches whole words and inflections ("prototype" no longer matches "type") and covers pay/buy/submit/confirm/publish/reply/unsubscribe/clear/erase/uninstall/reset/format. `ConfirmationMatchingTest` added.
- **V2 — voice path side-effect gate:** `VoiceAgentRouter` now asks for on-screen approval before any sub-agent that implements `WriteApprovalRequired` changes data (`ProductivityAgent`: calendar/notes/tasks/alarms/timers; `MemoryAgent`: deleting memories). No gate available ⇒ falls back to the chat pipeline, which enforces `AgentLoopSideEffectPolicy`. A declined request is answered "Okay, I won't do that." `AndroidAgent` is covered by V1 per element.
- **B1 — entitlement:** premium is stored with a Keystore-backed signature and a server expiry (`EntitlementSigner`, `SubscriptionManager.applyVerifiedEntitlement`); a bare `setTier(PREMIUM)` is refused.
- **B2 — Google Play:** `PlayPurchaseVerifier` writes a purchase claim; the `verifyPlayPurchaseClaim` Cloud Function verifies it with the Play Developer API and binds the token to one account before premium is granted.
- **B3 — Stripe:** tier and product come from the server response, not the deep link; each payment is granted once (payment-intent idempotency key). Needs the backend `/validate-session` contract documented in `StripeManager`; that backend is not in this repository.
- **B4:** `ChatViewModel.upgradeToPremium()` / `downgradeToFree()` removed.
- **K1:** Gemini keys go in the `x-goog-api-key` header (REST and Live WebSocket); error text is redacted with `SecretRedactor`. `SecretRedactorTest` added.

## Unreleased — Report 1 fixes, batch 7 (stage 34)

34. **Text attachment limit (item 31, decision: reject):** over `MAX_TEXT_CONTENT_CHARS` (512,000) the send is still rejected, never truncated, but now with `TEXT_ATTACHMENT_TOO_LARGE` ("Text attachments must be 512 KB or smaller") instead of the misleading "could not read the file". Static only, not compiled.

## Unreleased — Report 1 fixes, batch 6 (stages 29–33)

Source: AIRI report 1. Static verification only: no Android SDK / Kotlin compiler was available, so nothing below was compiled or run. Item 36 (running the tests) is left to the owner. Items covered: 2, 7, 27, 30, 5.

29. **Questions on the device model (item 2):** `QuestionToolExposurePolicy` keeps `current_time` and `memory_recall` (no `web_search`: the device may be offline and its schema is the largest) for plain questions and unclassified requests on the local model, but only when at least 3,072 tokens of content window are free (`LOCAL_MIN_AVAILABLE_TOKENS`). Smaller windows stay tool-free. Remote behaviour is unchanged.
30. **Implied connector reads (item 7):** `CapabilityIntentDetector.impliedReadTargets` maps personal-data phrases that do not name a service ("بريدي", "مستودعاتي", "my inbox", "my repos", "مواعيدي") to the Google or GitHub connector, for READ only. A write still needs the connector to be named, and a connector's tools are exposed only when it is connected and healthy.
31. **Credit denial (item 27):** a denied credit now stops the request (message `err_credit_denied` in en/ar/es/zh, paywall trigger) instead of only logging. If the metering call itself throws, the request continues and the quota gate stays the enforcement path.
32. **Pasted text summary (item 30):** a long paste converted to `pasted.txt` is shown as `[Attached file: pasted.txt · N lines, M chars]` plus its first 15 lines (at most 1,200 characters) instead of 200 characters. The full text still travels as the attachment.
33. **`write_file` tool (item 5):** new tool that saves one text file in the app-private `agent_files` folder. It is admitted exactly like `terminal_execute`: only with a task-owned durable context (`ALLOW_TYPED_FILE_WRITE`), through `PermissionGovernanceLayer` (`file_write`, MEDIUM risk) and the agent sandbox firewall (`WRITE_FILES`). `AgentFileWriter` validates a flat file name, an allow-list of non-executable extensions, 200,000 characters, 200 files, no NUL, no overwrite unless `overwrite=true`, and writes through a temp file and rename. `CapabilityIntentDetector.Capability.FILE_WRITE` ("اكتب ملف", "save it as a file") offers the tool and classifies the turn as a possible action. New `AgentFileWriterTest`.

Not done in item 5: **running code.** The terminal sandbox only admits a fixed list of read-only binaries (`TerminalExecutionPolicy`), and the device has no interpreter, so the model can write a script but not execute it. Adding that needs an execution engine and its own threat model.

Still open: 36 (tests need an Android SDK, owner will run them). Product decisions pending: 31 (reject vs truncate over 512,000 characters).

## Unreleased — Report 1 fixes, batch 5 (stages 27–28)

Source: AIRI report 1. Static verification only: no Android SDK / Kotlin compiler was available, so nothing below was compiled or run. Stages 1–26 are untouched. Items covered: 14, 38; item 31 was checked.

27. **Streaming retry (item 14):** `RemoteModelExecutor.generateStream` now retries up to `MAX_RETRIES` like `generate()`, but only while no token has reached the caller and only for transient failures (network errors, HTTP 429 and 5xx). Timeouts, 4xx and policy rejections are final; a failure after the first token is returned as-is so text is never duplicated. New `StreamRetryPolicyTest`.
28. **Engine label (item 38):** `ChatPresentationPolicy.humanModelLabel` no longer shows "Gemini Flash" for every Gemini id: it now distinguishes Flash Lite, Flash, Pro. The embedded free tier does use Gemini (`EmbeddedProviderConfig`: `gemini_flash`, `gemini_flash_lite`), so the name is real; the label now follows the configured model id.

Checked, not changed: **item 31** — the extraction limit is `AttachmentPolicy.MAX_TEXT_CONTENT_CHARS = 512_000` characters across all text attachments; exceeding it rejects the send with `attachment_text_extraction_failed`. Truncating instead would silently drop content, which the code deliberately avoids, so it stays a product decision.

Still open: 2 (local-model questions have no tools), 5 (no file-writing or code-running tool), 7 (connectors only when named), 36 (tests need an Android SDK). Product decisions pending: 27, 30, 31.

## Unreleased — Report 1 fixes, batch 4 (stages 19–26)

Source: AIRI report 1. Static verification only: no Android SDK / Kotlin compiler was available (Maven and the SDK hosts are blocked from the build environment), so nothing below was compiled or run. Stages 1–18 are untouched. "Stage" is a work-batch number; a stage can cover more than one report item. The batch covers 8 report items: 24, 37, 29, 32, 4, 8, 28, 3.

19. **Pre-first-token measurement (item 24):** `PromptSizeReport` and `PreFirstTokenTimeline` (new, `ai/prompt/budget`). `ChatViewModel` marks history load, RAG/knowledge, base prompt, tool catalog and capability snapshot, and logs one `AIRI_PREFILL stages=… sizes=…` line at the first token (system prompt, tool block, history in characters and estimated tokens; RAG and summary shown in brackets because they are already inside the system prompt). This is measurement only; the real numbers still have to be read from a device.
20. **Error strings (item 37):** `ErrorStringsParityTest` checks that en/ar/es/zh define the same string keys, that the timeout/partial/loading errors are non-blank, and that the stale "إجابات كاملة" wording is absent. The phrase is not present anywhere in the sources, so what the phone showed came from an older build.
21. **Execution lock (item 29):** `HybridOrchestrator` now uses `BoundedExecutionLock`. A caller that has to wait logs `EXEC_LOCK_WAIT`, and after 90 s (below the 120 s stall timeout) it gives up with an explicit "another request is still running" error instead of waiting without limit. The current holder is never cancelled by a waiter.
22. **Attachment admission (item 32):** an attachment alone no longer admits a visible execution graph (`direct_response_with_attachment`). The attachment still travels inline with the request; a request that also names a capability is admitted by that intent as before.
23. **Unclassified requests (item 4):** `QuestionToolExposurePolicy.selectUnclassified`. A request classified UNKNOWN with no detected capability and no user-selected skill gets `current_time`, `web_search`, `memory_recall` on remote providers and no tools on the device model, instead of every exposed tool. Requests that name a capability or a skill keep the full path.
24. **Terminal intent (item 8):** besides "terminal word + action word", a request is now treated as a terminal request when it pairs an execute verb (run/execute/نفذ/شغل) with a command noun, a known command (`ls`, `pwd`, `grep`, `git status|log|diff`) or an inline-code command. Execution is still bounded by `TerminalExecutionPolicy` and its approvals; this only decides whether the tool is offered.
25. **Send while replying (item 28):** when a new message interrupts a streaming reply, the text already streamed is saved (`GenerationResponsePolicy.shouldPersistInterruptedReply`) instead of being discarded. Sending still interrupts the current reply; an explicit Stop is unchanged and saves nothing.
26. **Agent entry (item 3):** documented and kept: a "direct_response" admission does not lock the agent out; `AgentLoop` publishes the graph when the model first calls a tool. Together with stages 10 and 23, entry to the tool path is no longer decided by keywords alone for questions and unclassified requests. Connector exposure (item 7) is still keyword-driven.

Still open: 2 (local-model questions have no tools), 5 (no file-writing or code-running tool), 7 (connectors only when named), 36 (tests need an Android SDK). Product decisions pending: 14, 27, 30, 31, 38.

## Unreleased — Report 1 fixes, batch 3 (stages 13–18)

Source: AIRI report 1. Static verification only: no Android SDK / Kotlin compiler was available, so nothing below was compiled or run. Stages 1–12 are untouched.

13. **Intent detector phrase lists (items 1, 3):** `CapabilityIntentDetector` normalized the input but matched it against raw phrase lists, so entries with hamza or diacritics ("ابحث على الإنترنت", "آخر الأخبار", "إيقاف", "أطفئ", "سطر الأوامر") could never match. Every list now goes through the same `normalize` (`normalizedTerms`).
14. **Short-term matching (items 3, 8):** device and terminal intent used plain substring tests, so "cat" matched "locate" and Arabic "فعل" matched "يفعل". Terms of four letters or fewer must now stand alone (`matchesAny`); longer phrases keep substring matching so Arabic clitics still work.
15. **Timeout message (items 9, 34):** `err_agent_loop_timeout` (en/ar/es/zh) said "processing deadline"; since stage 8 the limit is a stall timeout, so the text now says the model stopped making progress.
16. **Silent `false` (report §2.d):** `sendMessageInternal` logs `SEND_REJECTED reason=…` for busy, stale-session and empty-input rejections. Visible failures (model loading, cloud blocked, quota) keep their own messages.
17. **Latency measurement (report priority 3):** first-token latency (`AIRI_SPEED`) is logged in release builds, not only debug.
18. **Admission scope (items 3, 4, 32):** `ExecutionAdmissionPolicy` KDoc states that it only controls the visible execution graph, not tool exposure. New tests cover stages 13 and 14 in `CapabilityIntentDetectorTest`.

Still open on purpose: product decisions 14, 27, 30, 31, 38. Not addressed in this batch: items 5–8 (agent product shape), 24 (prompt size needs on-device measurement), 29 (execution lock; largely moot after the stage 4 and 8 cancellation fixes), 36 (tests need an Android SDK).

## Unreleased — Report 1 fixes, batch 2 (stages 6–12)

Source: AIRI report 1. Static verification only: no Android SDK / Kotlin compiler was available, so nothing below was compiled or run on a device. Stages 1–5 are untouched.

6. **Tool block budget (report §3.a):** `ContributorBudgetPolicy.toolCharsCapFor(providerId, …)`. Remote providers size the tool/skill block from a nominal 32K window (capped at 16,000 chars) instead of the on-device `ContextBudget.UNLOADED` (1,536 tokens, ~900 chars). Local requests are unchanged. Used by both `AgentLoop` and `ChatViewModel`.
7. **Brevity and tone (report §1):** a question is no longer BRIEF just because it is short (`ResponseDepthPolicy`: BRIEF only for explicit brevity markers or ≤3-word non-question chatter); "ماذا تعرف عن / what do you know about" is open-ended; BRIEF prompt text no longer contradicts the "natural length" rules; `AiriIdentityProfile.runtimeContext` gained a tone directive.
8. **Timeouts and output ceiling (items 9, 11, 12, 13):** the 120 s limit in `AgentLoop` is now a *stall* timeout measured from the last token or finished tool call (2× before the first token), enforced by `withStallGuard` which cancels the model call itself. Absolute ceiling 15 min. STANDARD answers get 2,048 tokens (was 1,024); code/program deliverables resolve to DETAILED.
9. **Tool-call parser (item 18):** `TextToolCallProtocol.parse` ignores envelopes shown inside non-envelope code fences (```kotlin, JSON blocks with other content), so explanations are no longer executed or sent into the repair loop.
10. **Questions keep read-only lookup (items 2–4):** `QuestionToolExposurePolicy` — SIMPLE questions on remote providers keep `current_time`, `web_search`, `memory_recall`; local runs and greetings/creative turns keep the no-tool fast path.
11. **Failure UX (items 26, 28):** `SubscriptionManager.refundMessage()` returns the daily message when a request ends with no delivered answer (timeout, failure, empty reply); sending while the model is loading now shows `err_model_still_loading` instead of silently doing nothing (en/ar/es/zh).
12. **Docs/comments (items 15, 16, 34, 35):** soft-limit token factors are documented as analytics-only, `shouldSemanticCut` as diagnostics-only, and the internal repair report got an addendum.

Not changed on purpose (product decisions or unverified): credit-denied soft warning (27), pasted-text preview and extraction-limit message (30, 31; the limit is 512,000 chars), streaming retry in the legacy `RemoteModelExecutor` (14), RAG isolation (25, already closed in the report), image engine name (38).

## Unreleased — Report 1 fixes, batch 1 (stages 1–5)

Source: AIRI report 1 (chat, send/reply, brevity, delay/failure, tools). Static verification only; app tests need an Android SDK and were not run on a device.

1. **Intent classifier (item 1):** action verbs, creative phrases and question markers are normalized with the same `InputNormalizer` as the input, so hamza spellings (أرسل/ارسل، أنشئ/انشئ) match.
2. **smartTrim (items 22, 23):** dedupe on full role+content instead of the first 60 chars; the latest assistant reply and any message with a code fence keep up to 16,000 chars instead of being cut at 600/2000.
3. **ReasoningStreamParser (item 17):** an unclosed `<think>`/`<analysis>`/`<reasoning>` block stays hidden while streaming but is released by `finish()` instead of erasing the rest of the reply.
4. **Cloud cancellation (item 10):** `invokeOnCompletion { disconnect() }` in the OpenAI (both paths), Gemini and Anthropic adapters only ran after the blocked read had ended. Replaced by `watchConnectionForCancellation`, which disconnects on cancel/timeout. The legacy `RemoteModelExecutor` (settings screens only) got the same fix and a corrected header comment (item 33).
5. **AgentLoop.callLLM (items 19, 20):** new `ToolCallStreamGate` does constant work per token, also hides the `{"tool_calls":[...]}` form, and releases held text when the finished reply is not a tool call.

## Unreleased — Release Closure Control

- Added [`docs/product/AIRI_RELEASE_CLOSURE.md`](docs/product/AIRI_RELEASE_CLOSURE.md) as the persistent execution ledger for the nearest Android Release Candidate.
- Added [`docs/product/PRODUCT_CONTRACT.md`](docs/product/PRODUCT_CONTRACT.md) to define the minimum end-to-end project journey, release blockers, evidence discipline, and explicit post-release scope.
- Reclassified `AIRI_PRODUCT_GAP_MATRIX.md` as long-term strategy and linked `AIRI_FINAL_CLOSURE_MAP.md` and `AIRI_FINAL_CLOSURE_STATUS.md` to the Release Closure authority.
- This entry does not claim a release artifact, signed APK/AAB, device verification, provider verification, legal review, or store publication.

## Phase 1: Feature Restoration

### ChatScreen (2786 lines restored from 540)
- **Restored** `AiriChatTopBar` — full top bar with model pill (status dot indicator), credits badge, history button, overflow menu
- **Restored** `AiriChatInputBar` — complete input bar with attachment picker, voice recording, live voice mode, smart reply chips, plan mode toggle
- **Restored** `UserBubble` — long-press menu (copy, edit, share, delete), attachment previews, timestamps
- **Restored** `AiBubble` — share, speak, feedback buttons, agent trace expand, streaming cursor
- **Restored** `AiStreamingBubble` — blinking cursor, animated content
- **Restored** `AiriHistoryPanel` — slide-in session list with timestamps and preview
- **Restored** `AiriModelPickerSheet` — local + cloud model selection with status indicators
- **Restored** Generation Settings dialog
- **Restored** Agent Plan bottom sheet with task status, step cards
- **Restored** Offline / integrity / context-reset banner system
- **Restored** `AdvancedChatInputBar` → now correctly calls `AiriChatInputBar` (was calling non-existent stub)
- **Improved** Empty state: animated cosmic orb (layered radial gradient, breathing scale + alpha) + 4 suggestion prompt chips
- **Improved** Bubble shapes: using `AIRIShapes.userBubble` / `AIRIShapes.aiBubble` tokens
- **Improved** Model pill: live status dot (green=local ready, blue=cloud, yellow=loading, violet=generating)
- **Improved** Credits badge: icon-first layout, `AIRIShapes.pill`
- **Improved** AI avatar: layered radial gradient orb, ExtraBold "A" letterform
- **Improved** Scroll-to-bottom FAB: gradient background, spring bounce animation
- **Improved** History panel header: refined close button affordance
- **Fixed** Import cleanup: removed unused `BIOMETRIC_WEAK` import; switched to `import com.airi.assistant.ui.theme.*`

---

## Phase 2: UI/UX + Performance + Human Quality

### Task 1 — Terminal (167 → 482 lines)
- Premium dark theme (`TermBg #090C18`, `TermPrompt CosmicAccent violet`)
- Live session status indicator dot (green when running)
- Command history panel — tap to re-use, shows last 20 commands with chevron hint
- Inline search with match highlighting and match count badge
- Up/Down arrow navigation through history (both hardware keys and on-screen buttons)
- Copy entire output to clipboard
- Syntax colouring: user input in cyan, errors in red, warnings in amber, output in off-white
- Search match highlighting with CosmicAccent background
- Hardware keyboard support: Enter to send, ↑/↓ to navigate history
- `BasicTextField` with custom cursor brush for true terminal feel
- Auto-scroll to latest line (skipped during active search)
- `commandHistory` exposed as `StateFlow<List<String>>` from `TerminalRuntime`

### Task 2 — Sandbox (303 → 405 lines)
- Matching dark terminal aesthetic (`SandboxTermBg #090C18`)
- Tab bar with per-session status dots (green=active, grey=idle)
- Session age display in header strip
- Polling log refresh (250 ms interval) for live output without Flow rewiring
- Cancel execution button during running state
- Restart session action in top bar
- Empty state with brand-consistent orb icon
- `BasicTextField` command input matching terminal style

### Task 3 — Secret Manager (187 → 336 lines)
- Info banner with lock icon explaining secure storage
- Per-provider card: icon, masked preview (`••••••••xxxx`), configured/not-configured badge
- Separate Edit and Delete actions with confirmation dialog for delete
- Copy key to clipboard action
- Snackbar feedback for save/delete/copy operations
- Delete confirmation dialog with SemanticError styling
- Edit dialog: show/hide toggle, proper password keyboard type

### Task 4 — Profile (206 → 366 lines)
- AIRI letterform orb avatar with initials fallback (first 2 chars of display name)
- Edit indicator overlay on avatar
- Account information section: sign-in provider (mapped to human name), member-since date
- Session section: Sign Out and Delete Account actions
- Delete account confirmation dialog (explains Firebase vs local data)
- `updateProfile()` writes to both `SharedPreferences` and Firebase user profile
- Snackbar confirmation on save
- All strings use `stringResource`

### Task 5 — Memory (195 → 306 lines)
- Inline search bar with animated show/hide (no search icon in top bar when not active)
- Animated empty state: pulsing radial halo around Psychology icon
- "No matches" state for empty search results
- Summary bar showing episodic memory count with CosmicAccent chip
- Message cards: role avatar (U/A), timestamp, 4-line preview with ellipsis
- Clear confirmation dialog with delete icon and SemanticError action button

### Task 6 — Localization
- **Fixed** `VoiceLiveOverlay.kt`: replaced hardcoded Arabic strings (`"جارٍ الاستماع…"`, `"AIRI يتحدث"`, `"جارٍ المعالجة…"`) with `stringResource(R.string.voice_listening_label)` etc.
- **Added** 60+ new string resources covering Terminal, Sandbox, SecretManager, Profile, Memory, About, Voice
- **Fixed** `VoiceSettingsScreen.kt`: Porcupine, Access Key, Vosk, Cloud Realtime strings
- **Renamed** "AI Library" → "AI Settings" in `strings.xml`, `values-es/strings.xml`, `values-ar/strings.xml`, `values-zh/strings.xml`
- **Fixed** `SecretManagerScreen.kt`: hardcoded "Secret Manager", "API Key", "Save", "Cancel" → `stringResource`

### Task 7 — Remove AI Design Fingerprints
- Replaced `Icons.Outlined.SmartToy` (generic robot) with:
  - `Icons.Outlined.AutoAwesome` in bottom nav Chat tab
  - `Icons.Outlined.Memory` in model picker and model list items
  - `Icons.Outlined.Psychology` in onboarding step
- `AboutScreen`: replaced robot icon with AIRI letterform "A" orb
- `WelcomeScreen`: replaced plain text `AIRI` with letterform orb (radial gradient, border)
- Removed AI-generated comment patterns (`// Task X:`, `// Phase N:`, `// [T33]:`) from 5 files
- Removed `"Task 3: Route through AuthService"` comment from `ProfileScreen`

### Task 8 — Navigation Smoothness
- Added `NavHost` transitions: `fadeIn + slideInHorizontally(it/12)` on enter, `fadeOut` on exit, reverse on pop
- All transitions use `FastOutSlowInEasing` at 180–220 ms — sub-frame latency on 60 Hz
- No recomposition changes needed: existing `launchSingleTop = true` + `restoreState = true` preserved

### Task 9 — Main Screen
- Removed centred plain-text "AIRI" from `WelcomeScreen`
- Replaced with letterform orb (same visual language as `AboutScreen`)
- `ChatScreen` empty state: no "AIRI" heading text — uses animated orb + subtitle only

### Task 10 — About Screen (92 → 225 lines)
- Replaced robot icon with AIRI "A" letterform orb (22dp radius, radial gradient, accent border)
- Version + build number in dedicated chip row
- Info cards redesigned: icon in 36dp accent-tinted box + title + body
- Navigation links: Technical Details, Licenses, Privacy
- `copyright_notice` string used for footer

### Task 11 — Security
- Gated `ChatViewModel` performance `Log.d` behind `BuildConfig.DEBUG`
- Verified all `Log.d` calls that reference model params are already DEBUG-gated
- `FirebaseAuth.getInstance()` calls: none remain in UI layer (routed through `AuthService`)
- `exported="true"` activities: only `MainActivity` (launcher) + `AiriAccessibilityService` (system-permission-protected) — both correct
- `SecureApiKeyStore` used for all API key storage — no plaintext preferences

### Task 12 — Human Code Quality
- Renamed `historyBuffer` (private `ArrayDeque`) in `TerminalRuntime` to avoid shadowing the new public `commandHistory: StateFlow`
- Added `_commandHistoryFlow: MutableStateFlow<List<String>>` with public `commandHistory` accessor
- Removed generated comment scaffolding from 5 files
- `DesignSystem.kt` tokens used consistently (no ad-hoc `RoundedCornerShape(20.dp)` in new code — always `AIRIShapes.pill`)
- All new screens follow: `Scaffold → LazyColumn/Column → Surface card` pattern
- No `TODOs`, no placeholder data, no `// TODO` blocks in new code

---

## Color System Improvements
- Added `CosmicAccentAlt` (#8B78F0) for gradient endpoints
- Added `SurfaceBase`, `SurfaceRaised`, `SurfaceFloating`, `SurfaceHighlight`, `SurfaceCard` ladder
- Added `GlassWhite`, `GlassWhiteBorder` glass tokens
- Added `NavIconInactive`, `NavIconActive` navigation tokens
- Added `LightBackground` / `LightSurface` / `LightOnBackground` for light theme
- All tokens documented with hue, usage, and relationship comments
