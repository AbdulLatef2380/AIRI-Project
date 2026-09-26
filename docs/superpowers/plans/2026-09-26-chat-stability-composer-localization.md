# AIRI Chat Stability, Composer Integrity, and AI Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans (recommended).

**Goal:** إصلاح Crash شاشة Chat، توحيد Composer بصرياً، جعل زر Scroll-to-bottom محايداً ومتوافقاً مع الثيم، وتطبيق اللغة المختارة على النصوص التي يراها المستخدم في مساري AI وexecution دون تغيير بروتوكولات النماذج أو الذاكرة أو الموصلات.

**Architecture:** الإبقاء على ChatScreen وAiriChatInputBar كمالكين للسلوك الحالي مع تعديل بنيوي صغير: حاوية Composer واحدة ثابتة، preview مشروط داخلها، وTextField متعدد الأسطر محدود بـ8 أسطر. نقل الرسائل التشغيلية المرئية إلى طبقة ترجمة مستقرة عند حد UI، مع إبقاء model IDs وHTTP payloads وlogs التشخيصية والعقود الداخلية باللغة التقنية غير المترجمة. معالجة Crash عبر إصلاح مصدر السباق/الحالة بعد reproduction، لا عبر catch عام.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android resources, StateFlow/Coroutines, JUnit JVM tests, Android CI.

**Spec:** `/home/ubuntu/upload/pasted_content.txt` (Chat Stability + Composer Integrity + Scroll-to-Bottom UI Fix، مع مطلب تعريب مساري `ai` و`execution`).

## Global Constraints

- النطاق المسموح: Chat stability، Scroll-to-bottom، Composer integrity، وتعريب النصوص المرئية في مساري AI وexecution فقط.
- ممنوع لمس Firebase، authentication، model routing، memory، connectors، scheduling، أو API wire contracts لهذه المهمة.
- Composer يجب أن يبقى **ONE VISUAL CONTAINER**؛ لا Divider أفقي يفصل attachment عن text.
- عند 8 أسطر يبقى الارتفاع محدوداً ويصبح النص قابلاً للتمرير؛ زر Expand داخل نفس الحاوية.
- لا استخدام `catch (Exception) { /* ignore */ }` ولا swallowing للاستثناءات.
- لا إعلان نجاح Build أو instrumentation إلا بعد تشغيله وتسجيل نتيجته.
- كل branch مطلوب: `cp-foundation` ثم نقل الالتزام المراجع إلى `main`، دون العمل على branch آخر.

## Capability Map

| Module | Responsibility | Depends on |
|---|---|---|
| `chat-stability` | تحديد وإصلاح Crash ومسارات السباق والحالة في Chat | existing ChatScreen/ChatViewModel contracts |
| `composer-ui` | حاوية Composer الواحدة، attachment، expand، scroll، RTL/LTR | `chat-stability` state invariants |
| `scroll-fab` | لون وسلوك زر العودة للنهاية | LazyListState contract |
| `ai-localization` | اللغة المختارة لنصوص AI الظاهرة للمستخدم | LanguageManager/resources |
| `execution-localization` | اللغة المختارة لرسائل execution الظاهرة للمستخدم | LanguageManager/resources |
| `verification` | static/unit/CI evidence and report | all modules |

Build order: `chat-stability` → `composer-ui` and `scroll-fab` in parallel → `ai-localization` and `execution-localization` in parallel → `verification`.

## Current Root-Cause Evidence

- `ChatScreen.kt` renders attachment preview at lines around 3194–3208, then draws `Divider(...)` at line 3209. This is the direct horizontal visual split.
- The expand action is rendered in a conditional row around lines 3285–3305 inside the Composer, but the threshold is `text.lineSequence().count() >= 5`, contradicting the required 8-line behavior.
- `BasicTextField` already declares `maxLines = 8`; this must be preserved and verified with long-text tests rather than replaced by an unbounded field.
- `ScrollToBottomFab` is a custom 48dp composable around line 3963; its full color implementation must be audited before changing it, including dark/light contrast and visibility threshold.
- Crash root cause is **not yet claimed**. It must be reproduced or bounded by a deterministic state/contract test before implementation.
- `LanguagePolicy` currently emits English prompt instructions by design; these are model-control text, not UI copy, and must not be mechanically translated unless a specific product requirement identifies them as user-visible.
- `ModelCatalog`, `OfficialSkillLibrary`, `ExecutionMode`, `CloudBackend`, `LocalLlamaBackend`, and skill implementations contain user-facing English labels/errors that need a resource-backed presentation boundary; provider IDs, model IDs, HTTP errors, logs, and protocol field names must remain unchanged.

## Review Focus

1. Rapid send/cancel and generation replacement must not deliver stale callbacks or crash the screen — add deterministic generation/cancellation tests.
2. Attachment add/remove during recomposition must preserve unique LazyRow keys and avoid invalid stale URI/file state — add state transition tests.
3. Composer at 1/8/9+/20 lines with and without attachments must retain one root container and bounded text height — add source contract tests plus Compose tests where available.
4. Scroll FAB must use theme tokens, appear only when away from end, and scroll to item 0 without duplicate launches — add policy tests.
5. Arabic and English selected locales must produce localized visible AI/execution labels while preserving API/protocol literals — add resource parity and locale presentation tests.

---

## Task 1: Crash Reproduction and Stability Boundary

**Files:**
- Inspect/modify only after reproduction: `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt`, `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`, attachment/domain state files as proven necessary.
- Test: existing Chat/ViewModel tests plus a focused new test under `app/src/test/java/com/airi/assistant/ui/` or the owning domain package.

**Interfaces:**
- Consumes current `generationId`, `isCurrentGeneration`, attachment list, LazyColumn stable keys, and coroutine ownership.
- Produces a deterministic regression test naming the exact broken invariant and a minimal fix only for that invariant.

- [ ] Inventory all `!!`, unchecked indexes, `first/last`, LazyColumn keys, `LaunchedEffect` keys, attachment file/URI reads, and coroutine callbacks in the inspected Chat paths.
- [ ] Reproduce each candidate with a minimal test or static contract; record the exact exception/invariant before changing code.
- [ ] Add the failing regression test first; it must fail for the pre-fix behavior and identify the state transition.
- [ ] Implement the smallest source-level fix; do not add broad exception swallowing.
- [ ] Run the focused test and the existing Chat-related tests; record exit code and failures.

## Task 2: Composer Unified Container

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt` (`AiriChatInputBar`).
- Test: `app/src/test/java/com/airi/assistant/ui/screens/` or source-contract test location already used by the repository.

**Interfaces:**
- Keeps the existing `AiriChatInputBar` parameters and callbacks unchanged.
- Keeps `attachments`, `onRemoveAttachment`, `onSend`, `onDraftTextChanged`, `showFullScreenEditor`, and attachment picker behavior unchanged.

- [ ] Add a failing source/Compose contract asserting one Composer root container owns attachment preview, text input, expand action, toolbar, and send action.
- [ ] Remove the attachment-to-input `Divider` at the identified line; do not replace it with another internal border/background.
- [ ] Keep `LazyRow` compact, keyed by stable `attachment.uid`, and inside the existing clipped/background Composer container.
- [ ] Change the expand visibility threshold from 5 lines to `>= 8` lines; preserve `maxLines = 8` so text scrolls after the limit.
- [ ] Keep Expand as an action inside the same container and use logical direction-aware placement rather than hardcoded physical left/right.
- [ ] Verify attachment add/remove and expand/collapse callbacks remain unchanged.
- [ ] Run source contract, XML/resource, and Compose/unit tests.

## Task 3: Scroll-to-Bottom FAB

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt`.
- Test: focused scroll policy/source contract test.

- [ ] Add a failing assertion for neutral theme colors and visibility tied to `isPinnedToBottom`/list state.
- [ ] Replace the current blue/primary treatment with existing neutral tokens (`AiriTheme.surfaceVariant`/`onSurfaceVariant`/`outline`) after checking `Color.kt` for dark/light contrast.
- [ ] Preserve 48dp touch target and accessible `cd_scroll_to_bottom` description.
- [ ] Preserve `scope.launch { listState.scrollToItem(0) }`; verify no out-of-range index is introduced with an empty list.
- [ ] Run the focused test and static checks.

## Task 4: AI Presentation Localization

**Files:**
- Modify/create a small presentation/localization boundary under `app/src/main/java/com/airi/assistant/ai/`.
- Modify resources: `app/src/main/res/values/strings.xml`, `values-ar/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml` as needed.
- Test: language/presentation JVM tests and resource parity checks.

- [ ] Enumerate user-visible labels/descriptions/errors from `ModelCatalog`, `OfficialSkillLibrary`, skill presentation objects, and AI runtime messages; exclude model IDs, JSON keys, protocol literals, logs, and prompt-control instructions.
- [ ] Define stable resource-key mapping by semantic identifier (not translated text), with English default and Arabic/Spanish/Chinese parity.
- [ ] Make AI UI presentation resolve through the selected Android locale; do not use `Locale.getDefault()` as the sole owner if the project LanguageManager provides an applied context.
- [ ] Add tests for Arabic and English selected locales and assert no missing resource keys.
- [ ] Preserve technical identifiers in model/provider metadata.

## Task 5: Execution Presentation Localization

**Files:**
- Modify/create presentation mapping under `app/src/main/java/com/airi/assistant/execution/` or the UI boundary that already owns `ExecutionErrorProjection`.
- Modify same language resource sets.
- Test: execution message mapping tests and resource parity.

- [ ] Separate stable error codes/types from display text; retain `CloudErrorType`, provider IDs, HTTP codes, and diagnostic logs as machine-readable data.
- [ ] Map visible messages such as network blocked, selected model unavailable, cancellation, local model not loaded, and cloud provider failures to resource-backed strings with arguments.
- [ ] Ensure Chat UI and execution status surfaces consume localized presentation text, not raw English backend messages.
- [ ] Add Arabic/English tests for each error class and confirm secrets/provider raw response bodies never enter localized UI.

## Task 6: Full Verification and Branch Delivery

**Files:**
- Test additions from Tasks 1–5.
- Documentation: update a scoped verification note under `docs/` only if required by repository convention.

- [ ] Run `python3 tools/airi_runtime_simulation.py`.
- [ ] Run `python3 tools/verify_core_changes.py` and `python3 scripts/airi_localization_health.py --strict`.
- [ ] Run `git diff --check` and XML parsing for every `values*/strings.xml`.
- [ ] Run affected JVM tests and `./gradlew :app:testDebugUnitTest` when Android SDK is available; otherwise explicitly report that local build was not executed.
- [ ] Run Android CI and instrumentation/UI tests through GitHub Actions; do not claim runtime verification until the workflow succeeds.
- [ ] Review the complete diff for scope leakage: no model routing, memory, connectors, scheduling, Firebase, or authentication changes.
- [ ] Commit on `cp-foundation`, cherry-pick the reviewed commit(s) to `main`, push both, and verify clean branch states.

## Open Questions / Assumptions Requiring Review

1. “تعريب مساري AI وexecution” is interpreted as **user-visible presentation text** (labels, descriptions, UI errors, status messages), not source comments, model-control prompts, API payloads, logs, provider/model identifiers, or skill internal protocol names.
2. The supported selected locales are the repository’s existing resource locales: Arabic, English, Spanish, and Chinese. No new locale is introduced without a project requirement.
3. Crash root cause is intentionally marked unconfirmed until reproduction/static evidence identifies the exact invariant.
4. The full-screen editor is a separate modal editing surface; the one-container requirement applies to the compact Composer, not the intentionally separate full-screen dialog.
