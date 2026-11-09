# AIRI — Changelog

## [Unreleased] - Part 5 batch 8 (full request/answer in Room, merged run source, diagnostics gated)
- Room run store schema 2 (additive migration): full request and answer, so runs rebuilt from Room are exact.
- `AgentViewModel.traces` merges in-memory runs with Room-only runs, bounded by the retention level; delete/clear cover both.
- Raw "full details" screen reachable only in debug mode. Unit tests added; not compiled in this environment.

## [Unreleased] - Part 5 batch 7 (Room fallback, live model rows, screenshot references)

Nothing was compiled or run (no Gradle, Kotlin compiler, Android SDK or network here). Script checks only; run `./gradlew :app:testDebugUnitTest :app:airiUiHardcodedTextLint` and `:app:connectedDebugAndroidTest`.

- Room read path: `AgentRunRebuilder` rebuilds a read-only `AgentTrace` from the Room rows; `AgentRunViewModel` uses it (once per opened run) when the in-memory store no longer holds the run, so old runs open instead of showing "missing". Approximation by design: the title stands in for the request, the preview for the answer, arguments are the stored previews; a run still marked RUNNING opens as interrupted. The list, the chat card and `AgentTraceDetailScreen` still read `AgentTraceManager`, so the screen is not removed yet.
- Live model rows: `ExecutionStatusBus.modelTurnTicks` makes `LiveTraceBridge` republish when a turn is recorded, so a turn shows without waiting for the next trace event.
- Screenshot references without a library: `PixelComparator` (JVM-tested) and `AgentRunScreensInstrumentedTest.screenshotsMatchTheReferenceImages` compare captures with `androidTest/assets/run_goldens/*.png` (2 per channel, 0.5% of pixels), write actual + diff on failure. No reference images exist yet: record them once (`-Pandroid.testInstrumentationRunnerArguments.recordGoldens=true`) and commit them; until then that test fails by design.
- Tests: `AgentRunRebuilderTest`, `PixelComparatorTest`.

## [Unreleased] - Part 5 batch 6 (per-turn model rows, approval notification, step retry)

Nothing was compiled or run (no Gradle, Kotlin compiler or network here). Brace balance, XML validity, string keys per locale and `airi_ui_hardcoded_text_lint.py` were checked by script; the new unit tests were not executed. Run `./gradlew :app:testDebugUnitTest :app:airiUiHardcodedTextLint`.

- Per-turn model rows: `AgentLoop` records every provider request (`ModelTurnRecord`: provider/model actually serving, tool choice, finish reason, estimated prompt/output tokens, duration) into `ExecutionStatusBus` (max 64 per run, evicted with the trace). The mapper and the live bridge merge them as `AgentStepType.MODEL_TURN` steps, so they reach Room too. They are kept out of the raw trace-event list on purpose (the plan panel and legacy screens render every event).
- `AgentTrace.stepCount/successCount/hasErrors` ignore model turns; model turns are not counted against `MAX_STEPS`; a plain reply with only model turns is still not an agent run.
- Run screen: model rows interleaved with work rows by time (`buildFeed`), a switch row wherever the serving target changes, finish-reason and tool-required wording. Run duration now includes model time (it sums all step durations).
- Waiting-approval notification: posted when the approval gate opens while the app is in the background, cancelled when the gate closes; own high-importance channel; opens the live run; generic lock-screen text.
- Retry one step: the failed-step action sends a scoped follow-up draft ("retry only this step") instead of re-running the whole task. It is a request to the agent, not a replay of the tool, because stored arguments are truncated and redacted.
- New strings x 4 languages; tests in `ModelTurnTest.kt` and `RunNotifyAndFeedbackTest.kt`.
- Still open: UI reading from Room (and removing `AgentTraceDetailScreen`), golden screenshots (needs a library and `verification-metadata.xml`), live model rows can lag until the next trace event.

## [Unreleased] - Part 5 batch 5 (editor, diff, replay, notification, states, rating reason)

Nothing was compiled or run (no Gradle, Kotlin compiler or network here). Brace balance, XML validity, resource keys per locale and `airi_ui_hardcoded_text_lint.py` were checked by script; the new unit and device tests were not executed. Run `./gradlew :app:testDebugUnitTest :app:airiUiHardcodedTextLint` and `:app:connectedDebugAndroidTest`.

- Workspace sheet: tap a file for a read-only editor (syntax colors, line numbers) with Current / Original / Diff tabs; new Replay tab steps through the recorded writes with a slider and "Jump to live".
- `AgentFileHistory` (new): `write_file` keeps before/after snapshots per execution (capped: 60 per run, 30 runs, 200,000 chars). Contents are not redacted; they are deleted with the run, "clear logs" and account deletion.
- `LineDiff`, `SyntaxHighlighter`, `FileHistoryModel` (pure, tested); `CodeViewer`, `WorkspaceSheet` (Compose).
- Long-task notification (`AgentRunNotifier`): runs of 20 s or more with tool steps that end while the app is in the background, not stopped by the user; opens `airi://agent/run/{id}`; lock-screen text is generic.
- Run screen: dedicated look for waiting-approval and cancelled runs (icon, color, banner), a stop button for the live run, optional reason after a thumbs-down (stored on the device, removed with the run).
- Fix: deleting a run, clearing logs or deleting the account now also removes the Room run row (it used to stay), the file snapshots and the rating reason. `AgentTraceManager.onTracesRemoved` carries the scope (`SOME`, `CLEAR_LOGS`, `WIPE`). Room run store keeps at most 200 runs.
- 26 new strings x 4 languages; `AgentRunScreensInstrumentedTest` (approval card, PNG capture en/ar x light/dark).
- Still open: UI reads from Room, per-turn model rows, waiting-approval notification, golden screenshots. See `docs/design/AGENT_RUN_SCREEN_PART5_PROGRESS_AR.md`.

## [Unreleased] - Part 5 batch 4 (run card, approval, footer, workspace)

Nothing was compiled or run (no Gradle, Kotlin compiler or network here). Brace balance, XML validity, resource names per locale and `airi_ui_hardcoded_text_lint.py` were checked by script; the new unit tests were not executed. Run `./gradlew :app:testDebugUnitTest :app:airiUiHardcodedTextLint`.

- Chat: the "Agent · N" badge is replaced by a run card (title, status, live timer, real step count, Open). No card for a conversation without tool steps. `AiBubble` falls back to the old badge when no `onOpenRun` is given.
- Run screen: model rows (provider/model, estimated tokens, first-token latency, provider switch with reason), inline approval card with countdown, footer (follow-up drafts, thumbs rating, run again, copy diagnostics), workspace bottom sheet (files, terminal), and real buttons for the suggested failure action.
- `ChatViewModel`: `ConfirmationRequest.requestedAtMs`; `confirmAccessibilityAction(approved, rememberForRun)`. "Allow for this run" is limited to non-destructive requests of the same action in the same execution and is never persisted.
- Added `RunExtrasModel.kt`, `RunCard.kt`, `RunExtrasModelTest.kt`, new strings in en/ar/es/zh.
- See `docs/design/AGENT_RUN_SCREEN_PART5_PROGRESS_AR.md` for what is still open.

## [Unreleased] - Part 5 agent run list

- Agent logs screen rebuilt as a run list: status badge, duration, model/provider, file/command/tool counters, one-line result preview, day grouping, filters, swipe-to-delete with confirmation, running run pinned first.
- New AgentRunScreen (header with live timer, plan strip, timeline, decisions, result) opened from the run list and the deep link.
- Added standalone Room run store (`AgentRun`, `AgentRunEvent`) fed from AgentTraceManager, `AgentRunViewModel`, `airi://agent/run/{id}` deep link, run screen state derivation.
- Added `airiUiHardcodedTextLint` (fails on new literal `Text("...")` in `ui/`) and `AgentRunListModelTest`.
- See `docs/design/AGENT_RUN_SCREEN_PART5_PROGRESS_AR.md`.

## Unreleased — Closure plan (part 4), B5 + B6 + B7 + B8 + B9

Verification: **nothing was compiled or run** (no Android SDK, Kotlin compiler or network in this environment). Brace balance and XML validity were checked by script only. New JUnit tests are **not executed**. Run `./gradlew :app:testDebugUnitTest` and open the log screens on a device.

**State found:** A (one trace view) and B1 (live trace) were already in this tree from the earlier entries below; nothing more was changed for them. B2–B4 and the copy half of B5 were done earlier too.

- **B5, open message:** the log detail screen has an "Open message in chat" button (hidden while the run is in progress, for legacy skill traces, and when the trace has no session or message id). `ChatViewModel.requestOpenMessage` loads the session if needed and publishes a `MessageFocusRequest`; `ChatMessageList` waits for the load, scrolls to the message, then clears the request (`MessageFocusPolicy`, pure and tested). A deleted message, a failed load, or a message hidden by the chat search filter just drops the request. `AiriApp` navigates to chat with `popUpTo(CHAT)` so Back does not return into the log.
- **B5, share:** a Share button sends the same plain-text trace as Copy through the system share sheet (capped at 100,000 characters). There is no file export.
- **B6, step detail:** tapping a raw step opens a dialog with everything the log stored for it (inputs, output or error, duration), selectable and copyable.
- **B6, dropped steps:** `AgentTrace.droppedAtIndex` (stored in the trace file, default 0) records where steps were dropped, and the raw list shows a marker row at that position ("N steps were dropped here"). The mapper keeps the first step and drops from the middle (index 1).
- **B6, readable step names and kept detail:** `ChatExecutionTraceMapper` named tool steps after the opaque action id; it now uses the tool name from the event summary. A successful tool step used to show only "Tool completed: X" because its `detail` was dropped; it now shows the detail. This changes what existing steps display.
- **B6, result file:** a successful `write_file` step shows the same Save/Share/Open card as chat when the file is still in `agent_files/` (`AgentFileCards.fromExistingFile`; same name and folder checks as the chat card).
- **B6, NOT done, by design:** the full tool output is **not** available. Trace events never carried it: `AgentLoop` records only "Source …; result length N chars", and `appendTrace` clamps `detail` to 240 characters. Storing raw tool results would be a privacy decision, so I did not do it. The step dialog says the log keeps at most 500 characters per step output.
- **B7, checked by reading the code only, not by running it:**
  - "Executing 0/13" stuck after the reply: `onGraphCompleted` and the cancel path set a terminal stage and `isWorking = false`, and `PlanPanelVisibilityPolicy` shows terminal stages. I found no path that leaves EXECUTING after a terminal event, but a failed or killed run that never reaches either call was not traced.
  - Repeated "Model unloaded": `LlamaManager.unloadModel` emits it only when a local model or session existed (`hadLoadedModel`), so a cloud model does not trigger it. Two quick unloads could both see the flag before the first clears it; I did not change that.
  - Errors as chat bubbles: provider errors go to `ExecutionErrorProjection` and `ExecutionErrorBubble`, not to saved messages. I did not check every call site.
  - Flipped RTL punctuation: the user bubble sets the layout direction from the text (`chatTextDirection`, covered by `ChatPresentationPolicyTest`).
- **B8:** not fixable in code. New `docs/FIREBASE_API_KEY_RESTRICTION.md` lists the exact console settings (package, SHA-1, API restrictions). The key stays in `app/google-services.json` because the Gradle plugin needs it.
- **B9, tests added:** `MessageFocusPolicyTest` (7), `ChatExecutionTraceStepDetailTest` (9: names, detail, params, cap marker, store round trip, old files), `AgentFileCardExistingFileTest` (5). **Still not covered:** the Compose screens themselves (`AgentLogsScreen`, `AgentTraceDetailScreen`, the step dialog, the scroll-to-message effect) have no UI test; the `androidTest` setup exists but I wrote none.
- **Check on a device:** scroll position after "Open message" (the chat list also scrolls to the bottom when messages load, and I did not confirm the order); the step dialog on long output; the marker row position.
- **New strings** in 4 languages: share, open message, output-limit note, dropped-steps plural (Arabic has all six forms).

## Unreleased — Closure plan (part 4), A: one trace view

Verification: not compiled or run. Brace balance and XML validity checked by script only; new tests are **not executed**.

- **Problem (A):** three trace UIs. The chat panel used the grouped/tree model (`UnifiedExecutionTraceProjector`); the log detail screen used the old `AgentTrace/AgentStep` model, which has no category or tree.
- **Log detail now shows the same timeline as chat.** `AgentTraceDetailScreen` renders `InlineExecutionTrace` (categories, step tree, counters) from the execution's `ExecutionTraceEvent`s: live from `ExecutionStatusBus.trace` while running, otherwise from the bus history or `ExecutionTraceArchive` (by `messageId`), loaded off the main thread. Combined with B1, a running task now updates in place in the log.
- **Raw steps kept as a fallback:** the old step cards are hidden behind a "Show raw steps" toggle when the unified timeline exists, and shown by default when it does not (old traces, plain runs, archive missing). New strings in 4 languages.
- **`executionTraceAdmitted(...)`** (new, `InlineExecutionTrace.kt`): tells a screen whether the panel would render, so it can pick the fallback instead of showing an empty area.
- **Tests:** `ExecutionTraceAdmittedTest` (4 cases).
- **Not done:** the developer screens (Runtime/Diagnostics, Effect Debug Panel) are untouched and I did not check whether their registered agents are wired to chat. The list screen still reads `AgentTrace`; `AgentTrace/AgentStep` was not retired, because the log store and persistence depend on it. Old logs whose events were never archived (before the archive existed or past its retention) show only raw steps. The chat panel's nested list is height-limited (about 238dp) and I assumed that nests safely inside the detail `LazyColumn`; check scrolling on a device.

## Unreleased — Closure plan (part 4), B1: live trace

Verification: not compiled or run (same limits as the entry below). Brace balance and XML validity checked by script only; new JUnit tests were written but **not executed**.

- **Problem (B1):** `AgentTraceManager.startTrace/addStep` were only called from tests, so the log screen learned about a run only after the answer was saved.
- **`LiveTraceBridge` + `LiveTraceProjector` (new, `ai/agent/trace`):** collects `ExecutionStatusBus.trace` and publishes a snapshot of the running execution through `AgentTraceManager.publishLive`. Started once in `AIRIApplication` next to `attachStore`. `ChatViewModel` is **not** changed. The snapshot uses the id `trace-<executionId>`, the same id the chat path uses for its final record, so the final record replaces it.
- **`AgentTrace.inProgress`:** new flag. In-progress snapshots are never written to disk (`persistSnapshot` filters them); a snapshot abandoned for more than `STALE_TRACE_MS` is dropped on the next live publish.
- **Safety rules:** a late live update never overwrites a final record that already has a `messageId`; `removeLiveTrace` only removes still-running snapshots; a run with no finished step yet (plain chat) is not shown, matching the chat path.
- **UI:** list rows and the detail screen show a spinner and "In progress" (4 languages) instead of "Failed" while running. `AgentViewModel` now refreshes the open `selectedTrace` when its trace changes, so the detail screen updates live.
- **Limits:** the mapper only turns *finished* tool/step events into steps, so a tool that is currently running does not appear until it ends. A run killed with the process leaves no record (live snapshots are memory-only). Not done: an AgentTraceManager `startTrace/addStep` caller in production (the bridge replaces the need for it), cancel-from-log button.
- **Tests:** `LiveTraceBridgeTest` (7 cases: id parity with the final record, finished flag, no duplicates, late update, plain chat hidden).

## Unreleased — Closure plan (part 4), agent-log screens

Scope: items B2, B4 and the log half of B5/B6 from part 4. Verification: **nothing was compiled or run** (no Android SDK or network here). Brace balance and XML validity were checked by script only. Run `./gradlew :app:testDebugUnitTest` and open both screens on a device.

- **Localization (B2):** `AgentLogsScreen` and `AgentTraceDetailScreen` no longer hard-code English ("traces recorded", empty-state hint, "step(s)", "hidden", "Has errors", "Execution Successful/Failed", "N steps", truncation note, "OUTPUT"/"ERROR"). New strings and plurals added to `values`, `values-ar`, `values-es`, `values-zh` (Arabic has all six plural forms). The existing `traces_recorded` string is reused.
- **Dates (B2):** new `TraceFormatting.kt` formats with `DateFormat.getBestDateTimePattern`, so month names and ordering follow the locale instead of `MMM d, HH:mm`. Per-step times (`HH:mm:ss.SSS`) are unchanged.
- **Clear-all confirmation (B4):** the Clear button now opens a dialog showing how many traces will be removed.
- **Delete one trace (B4):** `AgentTraceManager.deleteTrace(id)` (memory + store), `AgentViewModel.deleteTrace`, a delete button on each list row and in the detail top bar, both behind a confirmation. Two unit tests added to `AgentTraceManagerLifecycleTest`.
- **Search (B4):** a search field in the list filters by input and final result text.
- **Copy (B5):** the detail screen copies the trace as plain text (already redacted by `AgentTraceManager`).
- **Deliberately not changed — provider error text (B3):** `CloudBackend.buildUserErrorMessage` stays English because `ExecutionFailurePolicy.classify` matches those exact phrases (and `ExecutionFailurePolicyTest` asserts them). Translating it would silently break classification. The user-facing text already goes through the localized `ExecutionErrorProjection`; I did not verify every path uses it.
- **Not done:** a single unified trace UI (A), live `startTrace/addStep` in production (B1), "open message" link (B5), full-output view and dropped-step display (B6), checking the old-screenshot bugs (B7), `google-services.json` key restriction (B8, a Google console task), tests for the trace screens (B9), and the Manus-style features in section C.

## Unreleased — Closure plan (part 3), gap pass

Checked each item of the closure plan against the code first: most of phases 1–2 (coding-task detector, `CodeTaskGate`, `ActionCompletionGuard`, REQUIRED tool choice, skills catalog, OpenRouter `supported_parameters`, no synthetic COMPLETED for plain chats) were already in this tree. Only the gaps below were changed.

Verification: the pure-Kotlin parts were compiled with the Kotlin 2.0.21 compiler bundled in Gradle and their JUnit tests were run (27 tests passed: planner, model support, tool-choice JSON, coding-task detector, trace success rule). Android-dependent code (`NativeToolCallWire` uses `org.json`, adapters, `ChatViewModel`) was **not** compiled or run; run `./gradlew :app:testDebugUnitTest`.

- **Build fix:** `AgentStep.typeLabel` had a non-exhaustive `when` after `INTENT/PLAN/VERIFY` were added to `AgentStepType`; the module would not compile. Branches added.
- **`NativeToolCallWire` (item 1.6):** arguments that are not a JSON object (for example cut off by the output limit) were turned into `{}` by `encode`, so `AgentLoop`'s `argumentsValid` check never fired and a truncated `write_file` ran with empty content. Invalid arguments are now carried in the envelope (`invalid_arguments_raw`) and stay invalid after `decode`. Tests added to `NativeToolCallParserTest`.
- **`toolChoice` (item 1.3):** `ExecutionRequest.ToolChoice` gains `NONE` and `SPECIFIC` (+ `toolChoiceName`). `ToolChoiceJson` maps them: OpenAI Chat `none` / `{"type":"function","function":{"name"}}`, OpenAI Responses flat function form, Anthropic `none` / `{"type":"tool","name"}`, Gemini `NONE` / `ANY` + `allowedFunctionNames`. `SPECIFIC` with a blank name degrades to REQUIRED. Provider limits (for example forcing with extended thinking) were not checked.
- **`ModelToolSupport` (item 1.5):** NATIVE / TEXT_PROTOCOL / NONE, resolved from provider id, the OpenRouter catalog flag and a verified-local flag.
- **`AgentRequestPlanner` (item 1.1):** pure planner returning `RequestPlan` (kind, query type, confidence, exposed and blocked tools with reasons, required tools, tool choice, "unfulfillable" reason). **Not wired into `ChatViewModel` / `AgentLoop` yet**; they still use `ToolExposurePipeline`. Wiring a 6,000-line view model without a build was judged too risky.
- **Trace success rule (item 4.4):** `hasRecoveredFailures` no longer lets an earlier tool success hide a later unrecovered failure. Existing test expectation changed accordingly; `ChatExecutionTraceSuccessRuleTest` covers the four cases in the plan.
- **Not done:** `AgentEvent` unified model, trace persistence and "copy diagnostics" button, `TOOLS_EXPOSED` / `CAPABILITY_FILTERED` in the details screen, `check_syntax` / `run_python` tools, the MockWebServer acceptance suite S1–S10 (needs OkHttp test deps and a device build).

## Unreleased — Agent code-task path, item 7 (file card)

Static verification only (no Android SDK / Kotlin compiler available): nothing below was compiled or run. Run `./gradlew :app:testDebugUnitTest` before relying on it.

- **File card after `write_file`:** a successful `write_file` now shows a card under the assistant reply (file name, kind, size) with **Save** (copies to the public Downloads folder through MediaStore, no permission needed on Android 10+), **Share** and **Open in…** (chooser over a read-only `content://` grant, for apps such as Pydroid). New: `AgentFileCard.kt` (pure model/factory), `AgentFileExporter.kt` (FileProvider, share/open intents, MediaStore copy), `ui/components/AgentFileCardView.kt`. `ChatMessage.fileCards` is filled in `ChatViewModel` from the tool step and rendered by `AiBubble`.
- **The bridge:** files stay in `filesDir/agent_files/`; `file_paths.xml` now exposes that folder to FileProvider. A card is created only when the tool is `write_file`, the result is the writer's success message for the same name, and the file exists directly inside that folder. Failed, paused and approval-pending writes produce no card. The exporter re-checks the name against the folder on every action.
- **Writer message:** `AgentFileWriter.savedMessage` / `SAVED_PREFIX` hold the success text (wording unchanged) so the dispatcher and the card cannot drift apart.
- **Prompt:** `CODE_TASK_INSTRUCTION` tells the model the app shows a Save/Share card and forbids inventing a path or claiming a Downloads save.
- **Strings:** 12 `agent_file_*` strings in en/ar/es/zh.
- **Tests:** `AgentFileCardTest`, `AgentFileProviderPathsTest`.
- **Known limits:** the card is UI-only like `toolOutputs`, so it is not restored when the chat is reloaded after the app restarts (the file itself stays in `agent_files/`). Save to Downloads is unavailable below Android 10 (the app then points the person to Share). Not tried on a device: whether a given Python editor accepts the Share/Open intent.

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
