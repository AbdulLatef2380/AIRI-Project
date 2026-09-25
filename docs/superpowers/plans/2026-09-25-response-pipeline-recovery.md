# AIRI Response Pipeline Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove and harden the existing local/cloud response pipeline with the smallest safe changes, preserve request identity, prevent duplicate/late terminal delivery, and return both `main` and `cp-foundation` to a verified CI state.

**Architecture:** Keep the existing `ChatViewModel → AgentLoop → HybridOrchestrator → RuntimeRouter → LocalLlamaBackend/CloudBackend → persistence/UI` architecture. Do not introduce a new engine or provider. Add only contract-level guards/tests where tracing demonstrates a gap; keep local and cloud attempts isolated and preserve the existing exactly-once terminal guard.

**Tech Stack:** Kotlin/JVM unit tests, Android Gradle Plugin, Kotlin coroutines, llama.cpp/JNI bridge, existing cloud adapters, GitHub Actions.

**Spec:** `/home/ubuntu/upload/pasted_content.txt`

## Global Constraints

- Scope is limited to the AI Response Pipeline; no UI, composer, subscription, connector, memory, scheduling, voice, account, or broad architecture redesign.
- Never use `git reset --hard` or `git clean -fd`; preserve existing changes.
- No API keys, full prompts, credentials, authorization headers, or secret-bearing URLs in logs/tests/reports.
- One request must preserve `requestId`, `conversationId`/session identity, `executionId`, model identity, provider identity, and terminal ownership end-to-end.
- One generation may produce zero or more content events and exactly one terminal result, with explicit cancellation/failure exceptions.
- Cloud-only offline mode must produce an honest network/configuration error, never a local/cached/canned answer.
- Local generation must prove model readiness before dispatch and must not depend on cloud.
- Use the existing execution architecture; do not create a master engine or duplicate contracts.

## Review Focus

1. **Cloud model/provider consistency:** the selected provider and model must be the adapter request identity; test that stale shared registry state cannot silently replace the selected model.
2. **Cancellation isolation:** cancelling generation A must clear backend/native cancellation state before generation B; test A-cancel then B-success at the gate/backend boundary.
3. **Terminal delivery:** duplicate completion, empty completion, stale execution, and fallback after partial output must not create duplicate or cross-generation messages.
4. **Honest failure:** missing model, unavailable network, malformed stream, timeout, and provider HTTP failures must reach observable error state rather than a fake response.
5. **Persistence/UI boundary:** successful final text must be committed once to storage and represented by a stable message identity; failures must not leave endless generating state.

---

### Task 1: Historical baseline and current trace

**Files:**
- Read: Git history and `app/src/main/java/com/airi/assistant/execution/*`, `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt`, `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`.
- Create: `docs/reports/response-pipeline-recovery-2026-09-25.md`.

- [ ] Record clean pre-change status for `main` and `cp-foundation` worktrees.
- [ ] Review all response-adjacent commits dated 19–20 August, especially `84a0f329`, `9a019ea5`, `f64f1731`, `fb336ca2`, and the later known-good identity/delivery commits `3770e221`, `9122f3f6`, `2044f333`, and `4e52a0fd`.
- [ ] Document the actual path with source file, function, state, dispatcher/thread, and failure behavior for each edge.
- [ ] Record the smallest proven failure and distinguish it from compile-only CI blockers.

### Task 2: Contract regression tests

**Files:**
- Modify: `app/src/test/java/com/airi/assistant/execution/ExecutionIntegrityTest.kt`.
- Modify or create tests under `app/src/test/java/com/airi/assistant/execution/cloud/` only for pure adapter/model contract behavior.

- [ ] Add tests for identity preservation across retry copies and stale-generation rejection.
- [ ] Add tests for exactly-once terminal delivery and cancellation terminality.
- [ ] Add tests for provider/model selection behavior that can run without credentials or network.
- [ ] Run the focused tests and record output before implementation changes where a red test demonstrates the gap.

### Task 3: Minimal response-pipeline fix

**Files:**
- Modify only the traced runtime file(s), expected candidates: `CloudAdapterFactory.kt`, `OpenRouterAdapter.kt`, `CloudBackend.kt`, `HybridOrchestrator.kt`, or `LocalLlamaBackend.kt`.
- Modify corresponding focused tests.

- [ ] Preserve the request identity and selected model/provider at the request boundary; reject or surface mismatches instead of silently falling back.
- [ ] Preserve existing local model-ready and native cancellation behavior.
- [ ] Keep provider retry/fallback from appending a second response after partial output.
- [ ] Ensure every failure reaches the existing error/terminal state.
- [ ] Run focused tests and the complete JVM unit suite.

### Task 4: CI compile blockers only

**Files:**
- Modify the smallest source locations reported by the current CI compiler: `AdvancedInputBar.kt` and `ChatScreen.kt`, without redesigning UI behavior.

- [ ] Fix the non-composable `stringResource` calls by resolving strings in composable scope.
- [ ] Fix the explicit `BoxWithConstraints` width receiver usage.
- [ ] Fix the attachment click lambda return type so it returns `Unit`.
- [ ] Re-run `:app:compileDebugKotlin` and `:app:lintDebug`.

### Task 5: Verification matrix and report

**Files:**
- Modify: `docs/reports/response-pipeline-recovery-2026-09-25.md`.
- Create: `docs/reports/response-pipeline-recovery-matrix-2026-09-25.md` if the matrix is clearer as a separate artifact.

- [ ] Run local offline/online tests that the environment supports; explicitly mark unavailable device/network/model scenarios as limitations, never as PASS.
- [ ] Run `./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`, and `./gradlew :app:lintDebug` as permitted by the environment.
- [ ] Re-trace local and cloud paths after the fix.
- [ ] Include root cause, historical SHA evidence, exact invariant, changed files, actual test/build output, limitations, and no-scope-expansion check.

### Task 6: Apply and verify both branches

**Files:**
- Modify and commit the same minimal fix/tests/report in `/home/ubuntu/work/AIRI-main` and `/home/ubuntu/work/AIRI-cp`.

- [ ] Keep branch-specific changes intact; do not blind cherry-pick unrelated history.
- [ ] Commit separately on `main` and `cp-foundation` with descriptive messages.
- [ ] Push both branches with `git push origin main` and `git push origin cp-foundation` only after local verification.
- [ ] Monitor all GitHub Actions workflows for both pushed SHAs; inspect failed job logs and correct only in-scope blockers.
- [ ] Report exact SHAs, workflow conclusions, and any environment limitations.

## Official references to cite in the report

- Kotlin cancellation is cooperative: https://kotlinlang.org/docs/coroutines-cancellation.html
- Android coroutine best practices and lifecycle cancellation: https://developer.android.com/kotlin/coroutines/coroutines-best-practices
- Gemini streaming API contract: https://ai.google.dev/api/generate-content

---

## Self-review status

- Historical commits are identified from Git, not commit messages alone.
- The plan preserves the existing runtime and does not propose a new engine/provider.
- The plan distinguishes response-pipeline fixes from compile-only CI blockers.
- Device-only local inference and true network-off tests require explicit environment evidence and will not be claimed when unavailable.
