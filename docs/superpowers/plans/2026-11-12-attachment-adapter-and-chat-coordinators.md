# Android Attachment Payload Adapter and Chat Coordinator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Separate platform-neutral attachment metadata from Android payload values, then incrementally extract ChatViewModel owners without changing chat, session, attachment, model, or trace behavior.

**Architecture:** `core-domain` owns immutable attachment metadata and policy-facing values only. Android owns `Uri`, `Bitmap`, private paths, and payload adapters. `ChatAttachment` remains a compatibility facade during the migration and exposes the same existing call sites until each owner extraction is proven.

**Tech Stack:** Kotlin Multiplatform `core-domain`, Android Kotlin/Jetpack Compose, JVM unit tests, Android unit tests, existing Gradle verification scripts.

**Spec:** User-provided Phase 6 refactoring specification, especially 6.2 and 6.3.

## Global Constraints

- `core-domain` remains Android-free.
- No observable change to chat/session/attachments/model/trace behavior.
- Every extraction is independently tested and committed.
- New services consume commands and read-only state; they do not receive `MutableStateFlow`.
- `Uri`, `Bitmap`, `Context`, WorkManager, and Android logging remain in Android adapters.

## Review Focus

- Metadata must never retain or serialize `Uri` or `Bitmap` references.
- Camera payloads and picker payloads must preserve their existing nullable-source behavior.
- Existing attachment JSON must remain byte/field compatible for history reconstruction.
- Session ownership and attachment cancellation semantics must remain unchanged.
- Model lifecycle extraction must not duplicate collectors or publish stale state after cancellation.

### Task 1: Neutral attachment metadata contract

**Files:**
- Create: `core-domain/src/commonMain/kotlin/com/airi/core/attachments/AttachmentMetadata.kt`
- Create: `core-domain/src/commonTest/kotlin/com/airi/core/attachments/AttachmentMetadataTest.kt`

- [ ] Define `AttachmentKind` and immutable `AttachmentMetadata` with id, kind, display name, MIME type, size, uid, and file name only.
- [ ] Normalize display name and MIME through existing `AttachmentPolicy` without adding Android imports.
- [ ] Test metadata normalization and prove its public properties contain no Android payload type.
- [ ] Run `:core-domain:allTests` or the repository's available core-domain test task.
- [ ] Commit as `refactor: add platform-neutral attachment metadata`.

### Task 2: Android payload adapter compatibility slice

**Files:**
- Create: `app/src/main/java/com/airi/assistant/attachments/AndroidAttachmentPayload.kt`
- Modify: `app/src/main/java/com/airi/assistant/domain/ChatAttachment.kt`
- Create: `app/src/test/java/com/airi/assistant/attachments/AndroidAttachmentPayloadTest.kt`

- [ ] Define `AndroidAttachmentPayload` containing nullable `Uri`, nullable `Bitmap`, and nullable persisted private path.
- [ ] Add compatibility accessors to `ChatAttachment` without changing constructor call sites or JSON fields.
- [ ] Add a metadata projection that returns `AttachmentMetadata` and excludes all Android payload fields.
- [ ] Test picker, camera, and persisted-file cases plus existing marker output.
- [ ] Run targeted JVM tests and `tools/verify_core_changes.py`.
- [ ] Commit as `refactor: isolate Android attachment payloads`.

### Task 3: Metadata JSON boundary

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`
- Modify: relevant attachment metadata tests.

- [ ] Make JSON writing consume the metadata projection and add only the existing private stored filename field at the Android boundary.
- [ ] Keep history reconstruction fail-closed for invalid or out-of-root paths.
- [ ] Add a regression test for field compatibility and path exclusion.
- [ ] Run targeted tests and commit as `refactor: keep attachment persistence at Android boundary`.

### Task 4: ChatViewModel lifecycle owner extraction

**Files:**
- Create: `app/src/main/java/com/airi/assistant/ui/viewmodel/ModelLifecycleCoordinator.kt`
- Create: `app/src/test/java/com/airi/assistant/ui/viewmodel/ModelLifecycleCoordinatorTest.kt`
- Modify: `ChatViewModel.kt` only after the contract test is green.

- [ ] Extract idempotent startup, model load/unload, and teardown commands using constructor-injected ports.
- [ ] Keep `StateFlow` read-only at the coordinator boundary.
- [ ] Test repeated initialization, cancellation, and stale generation/session suppression.
- [ ] Commit separately as `refactor: extract model lifecycle coordinator`.

### Task 5: Attachment dispatch coordinator

**Files:**
- Create: `app/src/main/java/com/airi/assistant/ui/viewmodel/AttachmentDispatchCoordinator.kt` and its tests.
- Modify: `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt` after contract tests.

- [x] Move attachment admission and capability orchestration into a pure coordinator; keep Android payload handling and file staging in `ChatViewModel`/Android adapters.
- [x] Preserve stage/session/reply semantics and existing failure enums, including the in-flight dispatch guard.
- [x] Add contract tests for loading, generation, consent, unknown capability, and empty-input rejection paths.
- [ ] Run the Gradle test task once Android SDK is available, then commit separately.

### Task 6: Conversation execution coordinator and lifecycle teardown

**Files:**
- Create: `app/src/main/java/com/airi/assistant/ui/viewmodel/ConversationExecutionCoordinator.kt` and tests.
- Modify: `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt` and composition root wiring.

- [x] Extract the public conversation dispatch/cancel boundary while ChatViewModel retains presentation StateFlow and navigation/focus coordination.
- [x] Add an idempotent execution teardown boundary for active work, model stream, generation job, and delayed history cleanup; receiver, supervisor, voice bus, and skill runtime remain explicitly owned by their existing owners.
- [x] Add contract tests for dispatch, cancellation, and post-close suppression.
- [ ] Run the Gradle test task once Android SDK is available, then commit separately.
