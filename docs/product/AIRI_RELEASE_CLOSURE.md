# AIRI Release Closure

**Branch:** `cp-foundation`

**Program status:** `IN_PROGRESS`

**Release target:** Nearest Android Release Candidate supported by reproducible evidence

**Last reviewed source revision:** `d96c6ce8`

**Scope authority:** [`PRODUCT_CONTRACT.md`](PRODUCT_CONTRACT.md)

> **Execution rule:** Work toward one complete Android user journey, not toward completing every row in the long-term roadmap. Independent blockers may progress in parallel, but a result is recorded only when its ownership, persistence, security boundary, failure behavior, and evidence are real.

## 1. Release decision boundary

A Release Candidate can be proposed only after all `RC-B01` through `RC-B10` in [`PRODUCT_CONTRACT.md`](PRODUCT_CONTRACT.md) have traceable evidence. The Release Candidate decision does not itself publish to a store. Publishing requires the separate signed-artifact, release-owner, legal, and store gates listed below.

| Work class | Treatment in this program |
|---|---|
| **Release blocker** | Owns part of the product contract, can prevent the defined user journey, or is required to create and verify a release artifact. It receives an explicit closure test and evidence. |
| **Parallel release quality** | UI defects, accessibility, RTL/LTR, error/loading/empty states, startup and performance defects that materially block the defined journey are fixed alongside blockers. General polish does not expand scope. |
| **Post-release / external** | Marketplace, teams, billing, admin, public API, webhooks, cloud fleet, desktop/VNC, advanced Canvas, and full browser automation are not release work. Calendar/provider work remains external unless a narrowly typed, owned release-path verification is available. |

## 2. Canonical acceptance journey

```text
New user
  → create/select project
  → add managed project file
  → admit scoped project context / RAG
  → ask AIRI
  → create durable task and plan
  → execute bounded safe local operation
  → review approval when required
  → Trust Center: allow once / deny
  → exact task/run/step continuation
  → Execution Center and bounded evidence
  → artifact linked to project/task/run/step
  → deliberate failure or close/reopen
  → same project and durable task state restored
  → release build, signing, release gates
```

The reference release path must favour a **local, owned, testable operation**. It must not depend on unconfigured OAuth, a live provider, or an external connector merely to demonstrate approval and recovery. Existing Calendar and GitHub paths remain useful typed-contract evidence, but do not become a release prerequisite without credentials, device evidence, and a deliberate scope decision.

## 3. Current state

| Release area | Current state | Existing evidence | Closure gap |
|---|---|---|---|
| Mission/task aggregate | Source baseline is implemented and targeted tests exist. | `MissionKernelTest`, `DurableTaskProductKernelTest`, core guard. | A single user-facing journey proof across close/reopen and invalid state recovery. |
| Project context and RAG | Scoped admission, retrieval ranking, and Project Home exist. | Admission/ranking tests, strict localization, CI. | Device path from file admission to response context and A/B runtime isolation. |
| Artifact provenance | Project/task/run/step metadata, hash, private artifact link, and bounded Library evidence exist. | v9 migration harness, provenance and durable-task tests, CI. | Journey-level artifact creation/read and device migration/accessibility proof. |
| Exact-step continuation | Owned project-file/GitHub continuations and a typed Calendar continuation exist with one-shot claim semantics. | Contract tests and source guards. | Choose and prove one local release-path approval/resume/deny/replay case on Android. |
| Project secrets | Project/connector-bound capabilities and no-global-fallback GitHub consumer path exist. | `SecretVaultTest`, integration tests, core guard. | Device Keystore/workspace-switch and runtime A/B consumption proof. |
| End-to-end isolation | A/B fixture composes files, knowledge, memory, secret capability, and artifact denial. | `ProjectResourceIsolationTest` compiles and CI instrumentation passed. | Explicit release-journey execution and device proof; no unified user-visible evidence view is required to release. |
| Recovery | Durable pause/claim/restart sweep contracts exist in owned paths. | Targeted tests and contracts. | Process-death/reopen proof for the reference journey on Android. |
| Privacy and data lifecycle | Consent gates, debug/release network separation, explicit local erase, and remote-first account deletion contract exist. | Core guard, strict locale checks, CI runs through `32692865948`. | Device confirmation/cancel paths and final artifact/privacy review. |
| Build/package | Debug, lint, release-source compile, instrumentation API 29, and native verification succeed in CI. | [Android CI run 32692865948](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32692865948). | Complete package/R8/mapping/hash and controlled signing on the release-authorized branch/environment. |
| External publishing | No false claim. | Matrix and audit documents enumerate required evidence. | Signing key owner, Play/legal/Data safety/privacy policy, store pre-launch, and real-device/provider gates. |

## 4. Active blocker ledger

| ID | Blocker | Owner path | Next proof | Status |
|---|---|---|---|---|
| RC-B01 | Mission/task aggregate through reopen | `MissionKernel`, `DurableTaskManager`, execution surfaces | Focused recovery scenario with persisted task/run/step integrity. | `OPEN` |
| RC-B02 | Provenance in the user journey | `ProductionAgentOrchestrator`, `ArtifactManager`, Library | Reference operation creates/links artifact; inspect owned evidence after reopen. | `OPEN` |
| RC-B03 | Approval and exact-step continuation | `TrustCenter`, typed continuation runtime, durable task product kernel | Local approved/denied/replayed continuation test plus Android execution. | `OPEN` |
| RC-B04 | Secret ownership enforcement | `SecretVault`, active workspace, owned consumer adapter | A/B capability denial/consumption runtime scenario without raw secret exposure. | `OPEN` |
| RC-B05 | Project isolation end-to-end | Project file/knowledge/memory/vault/artifact managers | Execute documented A/B fixture on Android and preserve sanitized evidence. | `OPEN` |
| RC-B06 | Full core user journey | Project, Chat, Tasks, Trust, Library, recovery UI | One traceable acceptance scenario across the journey above. | `OPEN` |
| RC-B07 | Android runtime verification | Device/API matrix | Real device/API26+ evidence for permissions, storage, WorkManager, UI, accessibility baseline, and process death. | `EXTERNAL` |
| RC-B08 | Security/privacy final verification | Privacy/permission/consent/deletion paths | Final-artifact audit and device confirmation paths. | `OPEN` / `EXTERNAL` |
| RC-B09 | Release build/R8 | Gradle release configuration and CI | `assembleRelease` or `bundleRelease`, R8, native check, mapping, SHA-256. | `OPEN` |
| RC-B10 | Signed APK/AAB | Release owner and protected signing environment | Controlled key use and signed artifact verification. | `EXTERNAL` |

## 5. Parallel quality guardrails

Fix a parallel quality issue only when it blocks the acceptance journey, introduces a privacy/security inconsistency, or invalidates required Android accessibility/layout behavior. Do not initiate broad redesigns or new capability families.

| Quality lane | Required release response |
|---|---|
| UI loading, empty, and error states | Repair only states reached by the reference journey; add localized resources for en/ar/es/zh. |
| RTL/LTR, TalkBack, font scale, and touch targets | Validate the reference journey on device; fix observed failures without speculative UI rewrites. |
| Performance and startup | Measure first. Fix only demonstrated release-path regressions, leaks, crashes, or unsafe background behavior. |
| Calendar | Retain current fail-closed typed path. Do not add OAuth, retries, or provider breadth to release scope absent a verified release need. |
| Browser | Retain public, user-confirmed handoff. Full DOM/authenticated automation is post-release. |

## 6. Evidence and milestone log

Every milestone entry must include the fields below. A source edit without a passing appropriate test is not a completed milestone, and a CI run does not substitute for a device or external result.

| Date / milestone | Current state | Completed | Blockers | Next action | Files changed | Tests / evidence | Commit | Remaining release risk |
|---|---|---|---|---|---|---|---|---|
| 2026-08-24 — Local device-data erase evidence | Local device-data erase is implemented as a separate confirmed local action. | Audit register and closure status record that it stops AIRI-owned work, wipes owned local stores, and locally signs out without remote/Firebase deletion. | Physical cancel/confirm/wipe and remote-account-retained proof; model downloads/provider/cloud data are outside the action. | Keep it in device matrix; do not broaden deletion claims. | `DataDeletionCoordinator`, privacy UI/resources, audit/status docs. | Core guard 76/76, strict localization, CI `32690168612` and `32692865948`. | `87797631`, `aea732d3`, `d96c6ce8` | No device proof, signed artifact, or store/legal evidence. |

## 7. Next action

**Audit the reference acceptance journey against live source ownership and existing tests, then fix the smallest demonstrable internal blocker.** The audit must identify one local reference operation for the approval/resume step and one reopen/failure scenario. It must not begin marketplace, billing, teams, cloud, desktop, VNC, public API, broad connectors, or full browser automation.

## 8. External release gates

| Gate | Required evidence | Why this cannot be inferred from the repository |
|---|---|---|
| Real Android device matrix | API/ABI/device results, permissions, file picker, WorkManager/Doze, process-death/reopen, RTL/LTR, TalkBack, local erase. | Hardware, Android Settings, OEM behavior, and visual/accessibility runtime. |
| Release artifact | Package output, R8 result, mapping, SBOM/dependency review where applicable, native output result, and SHA-256 hash. | Requires complete release packaging in the appropriate environment. |
| Controlled signing | Signed APK/AAB and verification from protected signing material. | Keys must remain outside source control and require a release owner. |
| Provider evidence | Consent, cancellation, revocation, recovery, and error behavior for every declared production provider. | Requires real credentials and third-party runtime. |
| Store and legal | Play pre-launch, Data safety, privacy policy, permission declarations, model/provider terms, package/version policy. | Requires publisher account, final artifact, and legal/policy review. |

## 9. Change control

This document governs release execution. [`AIRI_PRODUCT_GAP_MATRIX.md`](AIRI_PRODUCT_GAP_MATRIX.md) remains strategy and long-term product scope; it must not be used to pull a new product family into this closure program. [`AIRI_FINAL_CLOSURE_MAP.md`](AIRI_FINAL_CLOSURE_MAP.md) remains the detailed technical trace. When the two differ, this document controls release priority while the technical map records implementation detail.
