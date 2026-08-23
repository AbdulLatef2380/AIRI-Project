# AIRI Project File Editing Contract

> **Status:** `IMPLEMENTED` / `TESTED` / `BUILD_VERIFIED` for the local managed-text path. `ProjectFileEditRuntime`, typed continuations, project-file revision storage, Trust Center dispatch, Library review UI, JVM policy/kernel tests, and Android-test compilation are present. `RUNTIME_VERIFICATION_PENDING` applies to the Android instrumentation fixture and all device-specific usability, storage-failure, accessibility, and process-recreation gates.

## Purpose and ownership

A project-file edit is a **local, project-owned side effect**. It is neither a connector action nor an artifact update. `ProjectFileManager` remains the sole owner of managed project-file bytes and metadata; an editor runtime may never write arbitrary paths, create a parallel project identity, or overwrite a file directly from a composable.

| Domain | Canonical owner | Required boundary |
|---|---|---|
| Project identity | `WorkspaceRuntime.activeSession.sessionId` | The active workspace must equal the durable task's `projectId` and the target file's `projectId`. |
| Target content | `ProjectFileManager` | Only ready, managed, textual files below the edit-size limit are eligible. |
| Proposal payload | Private project-file edit storage | Candidate content and its diff are private local data; raw content is never stored in durable task JSON, logs, diagnostics, timeline summaries, model prompts beyond the explicitly scoped editing request, or artifact provenance. |
| Execution ownership | `DurableTaskManager` / `MissionKernel` | A proposal must bind one task, mission, project, run, and step. |
| Consent | `TaskApproval` plus one typed `ApprovalContinuation` | The continuation contains IDs and hashes only; it pauses the exact step before any write. |
| Evidence | `ArtifactManager` plus durable timeline | Bounded, non-sensitive summary and integrity hashes only; no raw file content or broad filesystem path is rendered as evidence. |

## Proposal lifecycle

The editor runtime creates a proposal only when all ownership coordinates are live and match. It snapshots the current target SHA-256, writes the candidate text to app-private proposal storage, records the candidate SHA-256, and derives a bounded line diff for review. The proposal is persisted so it survives process recreation, but it has no external URI, share surface, or secret-bearing continuation payload.

| State | Permitted transition | Required validation | Result |
|---|---|---|---|
| `DRAFT` | `PENDING_APPROVAL` | Target is managed, ready, textual, project-owned; current SHA matches proposal base SHA; candidate is bounded. | A durable approval and a typed exact-step continuation are persisted; the task pauses. |
| `PENDING_APPROVAL` | `CLAIMED` | Trust Center approved the matching approval; task/run/step/project ownership still matches; continuation is atomically claimed. | The file-write runtime may attempt one apply. |
| `CLAIMED` | `APPLIED` | Candidate file exists and hashes match; target SHA still equals base SHA; atomic write succeeds. | Project file metadata updates, stale knowledge index is removed, a bounded evidence artifact/timeline event is linked, and continuation completes. |
| `CLAIMED` | `FAILED` | Conflict, missing proposal payload, failed atomic write, invalid target, or evidence-link failure according to the implemented recovery rule. | No automatic retry; task step records a sanitized failure. |
| `PENDING_APPROVAL` | `REJECTED`, `EXPIRED`, or `CANCELLED` | User rejects, consent expires, or task is cancelled. | Candidate private content is deleted or retained only under bounded recovery retention; no target bytes change. |

## Approval and continuation requirements

The current connector invocation descriptor must not be overloaded as a pretend file operation. The continuation model must represent one of two explicit kinds: a connector invocation **or** a project-file write invocation. A file-write invocation contains only `proposalId`, `targetFileId`, `baseContentHash`, `candidateContentHash`, and an idempotency key. It excludes candidate text, backup paths, source URIs, credentials, tokens, and free-form user content.

The generic claim operation remains atomic and persists `CLAIMED` before any file I/O. Recovery enumerates approved continuations by kind and routes a file-write continuation only to the project-file edit runtime. Connector recovery must never claim a file-write continuation, and file-write recovery must never re-dispatch a connector. A claimed write is not transport-retried; a conflict or ambiguous I/O error requires explicit user recovery through a new proposal.

## Atomic apply and recovery

`ProjectFileManager` must implement a dedicated textual revision operation rather than expose its storage path. The operation validates project ownership and expected SHA-256, creates a private bounded backup, writes the candidate to a sibling temporary file, synchronizes it, atomically replaces the managed file when supported, and only then publishes refreshed metadata. If replacement fails, the original managed file remains the active version. If post-write metadata persistence fails, the file is reconciled from the on-disk SHA on next restore; it must not claim application success without that reconciliation.

After a successful content replacement, the old knowledge index is removed and the file returns to `NOT_REQUESTED` indexing state. AIRI may not silently re-index or inject the revised text into model context. Restoration creates a new explicit revision after the same ownership and integrity checks; it does not mutate the historical backup in place.

## UI, cancellation, and evidence

A project-file surface may show the target name, edit state, bounded line counts, bounded diff preview, base/candidate hash prefixes, exact task/run/step labels, and clear Approve once / Deny actions through Trust Center. It may not render an editable raw path, expose backup locations, or imply a write has occurred before the continuation finishes successfully. User cancellation delegates to the durable task cancellation path and invalidates pending proposals; a rejected or expired approval leaves the original file untouched.

On success, the runtime records an immutable project-scoped evidence artifact containing only a concise action summary and integrity prefixes, then links it to the exact durable step. The Artifact Library and timeline must be able to report the action without showing proposal text, the full diff, credentials, or hidden local paths.

## Acceptance evidence

| Gate | Required evidence |
|---|---|
| Ownership | Unit tests reject cross-project target, stale task/run/step, inactive workspace, and stale source hash. |
| Privacy | Tests assert candidate text is absent from continuation JSON, timeline details, artifact provenance summary, and diagnostics. |
| Consent | Tests prove the write cannot occur before approval, duplicate approval cannot apply twice, rejection/expiry/cancellation leave target bytes unchanged, and recovery routes by typed continuation kind. |
| Atomicity and rollback | Tests cover successful replace, failed write preserving source, private backup, explicit restore, oversized/non-text/read-only rejection, and stale-content conflict. |
| Knowledge | Tests prove the prior index is removed and no automatic re-index occurs after apply or restore. |
| Evidence | Tests prove a successful apply produces a project/task/run/step-owned bounded artifact and timeline link; cross-project reads fail closed. |
| Android runtime | Device verification covers file selection, diff readability, approval/rejection, process recreation, TalkBack, font scale, and storage failure. |

## Current implementation evidence

`ProjectFileEditRuntime` accepts only a running durable task whose active workspace, project, mission, run, and step all match the target managed file. It writes candidate text under app-private proposal storage; `ResumableProjectFileWrite` persists only proposal/file identifiers, two SHA-256 values, and an idempotency key. Trust Center approves through the existing governance bridge, and the typed runtime atomically claims the continuation before calling `ProjectFileManager.applyTextRevision`.

`ProjectFileManager` performs an app-private backup, writes a sibling temporary file, synchronizes it, replaces the managed file, refreshes content hash/preview/metadata, and resets the file's knowledge-index state. A successful apply creates a bounded evidence artifact and links it to the exact durable step; evidence-link failure triggers a one-time private restoration attempt and marks the continuation failed rather than retrying the write. Library offers an explicit user-authored replacement text and bounded diff review, then requests approval; it has no direct apply control.

| Evidence | Current truth |
|---|---|
| `DurableTaskProductKernelTest` | `TESTED` typed project-file continuation pause/claim and hybrid-descriptor rejection. |
| `ProjectFileEditPolicyTest` | `TESTED` bounded diff/persisted-proposal validation and descriptor field boundary. |
| `ProjectResourceIsolationTest.textRevisionRemainsProjectOwnedAndClearsStaleKnowledge` | `BUILD_VERIFIED` by `:app:compileDebugAndroidTestKotlin`; it has **not** been run on an emulator or physical device. |
| `:app:compileDebugKotlin` and strict localization | `BUILD_VERIFIED` for runtime, Trust Center recovery dispatch, Library review surface, and four-locale resource parity. |

## Explicit exclusions

This contract does not add a desktop IDE, shell editing, arbitrary filesystem write access, concurrent multi-user merge, cloud synchronization, arbitrary plugin code execution, automatic external commits, or a claimed DOM/browser editor. Those capabilities require separate ownership, policy, and runtime evidence.
