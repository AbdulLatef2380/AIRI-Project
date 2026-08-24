# AIRI Product Contract — Release Candidate Scope

**Branch:** `cp-foundation`

**Status:** `IN_PROGRESS`

**Purpose:** This contract defines the smallest coherent AIRI Android product journey that may be evaluated for a Release Candidate. It is not a catalogue of all planned platform capabilities and does not turn CI success into device, provider, signing, store, or legal evidence.

## Product promise

> AIRI lets a user work inside one private project: add a project-owned file, use only admitted project context when asking AIRI, create and follow a durable task, review an explicit approval before an owned side effect, inspect bounded evidence, and recover the same project and durable task state after the app is reopened.

The promise is intentionally **local-first and fail-closed**. A task must not gain access to another project's resources, raw secrets, or an external side effect simply because a related UI or class exists.

## Release Candidate journey

| Step | User-visible outcome | Required ownership and recovery invariant | Current evidence class |
|---|---|---|---|
| 1. Start and create/select a project | A single active project is visible and becomes the task context. | Project ID scopes files, knowledge, memory, artifacts, and project capabilities. | Source and automated coverage exist; device validation remains pending. |
| 2. Add a file and admit useful context | A managed project file may be imported, indexed only through an explicit local path, and later recovered from project trash. | File and knowledge reads fail closed across project boundaries; delete/restore clears and restores only the owned index. | Source and fixture coverage exist; picker/device runtime remains pending. |
| 3. Ask AIRI in the project | AIRI receives only budgeted, authorized project context and governed memory. | Cross-project candidates, private data, raw paths, and secrets must not enter the response context. | Policy/JVM and CI evidence exist; real index/device proof remains pending. |
| 4. Create a durable task and plan | A durable task has mission, task, run, step, and timeline ownership. | Invalid cross-project task, run, step, or approval records are rejected or normalized safely. | Implemented and targeted tests exist; process-death runtime proof remains pending. |
| 5. Perform one safe, local operation | A bounded local operation may produce a project-owned result or a private proposal. | The operation must preserve project ownership and create no unapproved external side effect. | Existing project-file proposal/apply path is the reference candidate; Android runtime proof remains pending. |
| 6. Request approval when required | Trust Center presents a localized, private review and allows denial or scoped approval. | Approval is tied to the exact task/run/step and continuation hash; generic conversation confirmation is never authorization. | Typed paths and tests exist; device/provider evidence varies by operation. |
| 7. Resume exactly once | The approved runtime resumes the same owned step or fails safely. | Claim, ownership, integrity, idempotency, and expiry are revalidated before I/O; duplicate execution is blocked. | GitHub/project-file/calendar typed paths are covered differently; reference release journey must use a locally verifiable path. |
| 8. Inspect execution and artifact evidence | The user can see bounded task/run/step evidence and an integrity prefix in the owned project. | Artifact writes and reads require matching project/task/run/step scope; raw sensitive payloads stay private. | Source, migration, and targeted tests exist; device evidence remains pending. |
| 9. Recover after close/reopen | The same project and durable task state are available without replaying an unclaimed side effect. | Restart sweep resumes only approved, unclaimed continuations; unknown or remote-running work does not silently execute. | Source contracts exist; process-death device verification is a release blocker. |

## Explicit non-goals before first Release Candidate

The following are not required to close this Android Release Candidate unless a capability is already on the defined journey and can be proved without widening its scope:

| Deferred scope | Release stance |
|---|---|
| Marketplace, teams, billing, administration, public API, webhooks, cloud fleet, desktop runtime, VNC, and advanced Canvas | `POST_RELEASE`; no implementation work is pulled into release closure. |
| Full browser automation and authenticated browsing | `POST_RELEASE`; public browser handoff stays user-confirmed and policy-bound. |
| Broad third-party connector coverage | `POST_RELEASE` or `EXTERNAL`; only an already-owned, typed path may be retained as evidence. |
| Calendar expansion | Keep only the existing typed contract; provider/OAuth/device work is external unless it is independently available and needed for a declared release path. |
| Product polish beyond defects that break the journey | Parallel quality work; it must not displace a defined blocker. |

## Release blockers

| ID | Blocker | Required proof before Release Candidate claim |
|---|---|---|
| RC-B01 | Mission/task aggregate | One project-owned durable task persists valid mission/task/run/step state through reopen. |
| RC-B02 | Artifact provenance | The journey creates or links an artifact with valid project/task/run/step provenance and denies cross-project access. |
| RC-B03 | Exact-step approval continuation | A user sees an approval, denial leaves no side effect, approval resumes once, and invalid/replayed continuation fails closed. |
| RC-B04 | Project secret enforcement | A project-scoped capability has no global fallback and cannot be consumed by another project. |
| RC-B05 | End-to-end project isolation | A/B project runtime proves file, knowledge, memory, secret, and artifact boundaries in the release journey. |
| RC-B06 | Core journey and recovery | The complete journey above works on Android, including a deliberate failure/reopen case. |
| RC-B07 | Android runtime | Target API/device evidence covers required UI, permission, storage, WorkManager, and process-recreation behavior. |
| RC-B08 | Security and privacy release audit | Permission, data-deletion, telemetry consent, logging, cleartext, secret handling, and disclosure evidence are reviewed against the final artifact. |
| RC-B09 | Release build and R8 | A reproducible release package completes shrinking, native verification, mapping capture, and hash recording. |
| RC-B10 | Signing APK/AAB | A release owner produces and verifies a signed artifact using controlled signing material. |

## Evidence discipline

A release gate can be marked successful only from its own evidence. `CI_VERIFIED` demonstrates the named CI gates on a commit; it does not demonstrate a physical device, real provider, Android Settings behavior, Play acceptance, signing, or a remote account action. External gates remain explicit rather than being simulated or deferred under a completed label.

## Source-of-truth links

| Document | Role |
|---|---|
| [`AIRI_RELEASE_CLOSURE.md`](AIRI_RELEASE_CLOSURE.md) | Execution control, blocker ledger, current state, and next action. |
| [`AIRI_FINAL_CLOSURE_MAP.md`](AIRI_FINAL_CLOSURE_MAP.md) | Technical ownership and detailed closure trace. |
| [`AIRI_FINAL_CLOSURE_STATUS.md`](AIRI_FINAL_CLOSURE_STATUS.md) | Milestone-level evidence status. |
| [`AIRI_PRODUCT_GAP_MATRIX.md`](AIRI_PRODUCT_GAP_MATRIX.md) | Product strategy and long-term roadmap; it does not set release scope. |
| [`PLATFORM_CAPABILITY_AUDIT.md`](PLATFORM_CAPABILITY_AUDIT.md) | Capability inventory and platform boundary. |
| [`../../CHANGELOG.md`](../../CHANGELOG.md) | Historical change record; not a release-status authority. |

## Milestone update rule

After every meaningful closure milestone, update `AIRI_RELEASE_CLOSURE.md` with **current state**, **completed evidence**, **blockers**, **next action**, **files changed**, **tests**, **commit**, and **remaining release risk**. Do not create a completed entry without a commit or traceable evidence reference.
