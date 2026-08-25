# AIRI Release Closure

**Branch:** `cp-foundation`

**Program status:** `FEATURE_FREEZE` / `RELEASE_PUBLICATION_CLOSURE`

**Release target:** Nearest Android Release Candidate supported by reproducible evidence

**Last reviewed source revision:** `ca881a1b` على `cp-foundation` و`main`؛ هذا هو signed release candidate الموثق.

**Scope authority:** [`PRODUCT_CONTRACT.md`](PRODUCT_CONTRACT.md)

> **Execution rule:** Work toward one complete Android user journey, not toward completing every row in the long-term roadmap. Independent blockers may progress in parallel, but a result is recorded only when its ownership, persistence, security boundary, failure behavior, and evidence are real.
>
> **Feature Freeze:** No new capability family, architectural redesign, connector breadth, marketplace, billing, teams, desktop/VNC, advanced browser runtime, Canvas, or long-term roadmap item may enter `cp-foundation`. A change is admissible only when it directly fixes a Release Candidate blocker, verifies a release artifact, or records required evidence. The operating loop is **BLOCKER → FIX → TEST → VERIFY → DOCUMENT → CONTINUE**.

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

## 3. Feature Freeze release gate

| Gate class | Decision during Feature Freeze | Required result |
|---|---|---|
| Internal product and CI | Close only a demonstrated release-path defect; no speculative P0 or scope expansion. | Focused test plus a passing relevant CI gate. |
| Release artifact | Produce only through the authorized release environment. | Release APK/AAB, R8 result, mapping where generated, native verification, and SHA-256 evidence. |
| Physical device | Do not simulate or infer it. | Recorded API/ABI/device result for the defined matrix, including local erase and the reference journey. |
| Live provider | Do not substitute mocks for external acceptance. | Credentialed consent, cancel/revoke, failure, and recovery evidence for each declared provider. |
| Store/legal | Do not infer acceptance from source or CI. | Publisher-owned Play, Data safety, privacy policy, declarations, and pre-launch evidence. |

Items such as deeper mission aggregates, Canvas, marketplace, teams, billing, advanced browser runtime, desktop PTY, broad connectors, and other Manus-like expansion remain **post-release roadmap** items unless they directly break the frozen release journey.

## 4. Current state

| Release area | Current state | Existing evidence | Closure gap |
|---|---|---|---|
| Mission/task aggregate | Source baseline is implemented and targeted tests exist. | `MissionKernelTest`, `DurableTaskProductKernelTest`, core guard. | A single user-facing journey proof across close/reopen and invalid state recovery. |
| Project context and RAG | Scoped admission, retrieval ranking, and Project Home exist. | Admission/ranking tests, strict localization, CI. | Device path from file admission to response context and A/B runtime isolation. |
| Artifact provenance | Project/task/run/step metadata, hash, private artifact link, and bounded Library evidence exist. | v9 migration harness, provenance and durable-task tests; `ProjectFileApprovalRecoveryTest` now creates and links an owned artifact after runtime reconstruction in CI API 29. | Device migration/accessibility proof and broader producer coverage. |
| Exact-step continuation | Owned project-file/GitHub continuations and a typed Calendar continuation exist with one-shot claim semantics. | Contract tests and source guards; `ProjectFileApprovalRecoveryTest` proves both approved local resume-once and denied no-apply/no-resume cases after runtime reconstruction in CI API 29. Library now rejects arbitrary task ownership: multiple eligible running tasks require an explicit user selection before proposal creation. | Device UI proof for approval/deny/replay and broader operation coverage. |
| Project secrets | Project/connector-bound capabilities and no-global-fallback GitHub consumer path exist. | `SecretVaultTest`, integration tests, core guard. | Device Keystore/workspace-switch and runtime A/B consumption proof. |
| End-to-end isolation | A/B fixture composes files, knowledge, memory, secret capability, and artifact denial. | `ProjectResourceIsolationTest` compiles and CI instrumentation passed. | Explicit release-journey execution and device proof; no unified user-visible evidence view is required to release. |
| Recovery | Durable pause/claim/restart sweep contracts exist in owned paths. | `ProjectFileApprovalRecoveryTest` reconstructs task, workspace, file, artifact, and proposal runtimes from app-private storage after approval, then proves one owned local apply and no second resume in CI API 29 (`32695492715`). | Physical-device process-death/reopen proof and UI-driven recovery. |
| Privacy and data lifecycle | Consent gates, debug/release network separation, explicit local erase, and remote-first account deletion contract exist. Commercial purchase, billing-history, marketplace, and community-skill routes are fail-closed during Feature Freeze; the merged Billing permission is removed from the release manifest. | Core guard 77/77, strict locale checks, and Android CI `32716171238` passed debug/unit/lint/release-source, API 29 instrumentation, and native verification. | Device confirmation/cancel paths and final artifact/privacy review. Any later commercial release needs its own provider, legal, and store evidence. |
| Build/package | Debug, lint, R8 release packaging, signed APK/AAB, `apksigner`, API 29 instrumentation, and native verification succeed in CI. On non-`main`, CI continues to produce unsigned structure evidence; `main` now produces the protected signed evidence set. | [Android CI run 32805967708](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32805967708) for `8868656e` succeeded end-to-end. Signed APK SHA-256 `cec8f92801d897619c110eef14434a747fa8190df8c326e62da9ac337d575f6e`; signed AAB `d8ad5ee0f12282e245d4f0004b1f606f3e7dd04a0d340940235b5e98ec2b10a7`; mapping `c988f131c9f44200a0b7be7419696d1ebadccce667ec019a7b72b8384b987b1c`; V2 certificate SHA-256 `EE:B5:1E:58:A3:71:85:F8:EC:1A:48:77:64:8F:9A:59:69:61:49:E7:0D:39:56:64:DF:F5:91:9C:82:C2:1A:F8`. | Signing is complete for this artifact; physical-device, provider, Play/legal/Data Safety, and rollout gates remain external. |
| External publishing | No false claim. | Matrix and audit documents enumerate required evidence. | Signing key owner, Play/legal/Data safety/privacy policy, store pre-launch, and real-device/provider gates. |

### 4.1 المرشح الموقع الحالي

المرشح الموثق للإصدار هو `ca881a1b`، وأعيدت المصادقة على آخر حالة Git عند `8868656e` دون تغيير كود التطبيق. نجحت [Android CI run 32805967708](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32805967708) في كل البوابات الداخلية وفي signed APK/AAB وcertificate verification، كما نجحت Architecture `32805967686` وDeep `32805967679` وOracle `32805967678`. هذه حالة **signed release candidate evidenced** فقط؛ صفوف P0 الجهازيّة موسومة `BLOCKED / REAL_DEVICE_ACCESS_REQUIRED` ولا تتجاوز متطلبات الجهاز والمزود والمتجر والقانون.

## 5. Active blocker ledger

| ID | Blocker | Owner path | Next proof | Status |
|---|---|---|---|---|
| RC-B01 | Mission/task aggregate through reopen | `MissionKernel`, `DurableTaskManager`, execution surfaces | Focused recovery scenario with persisted task/run/step integrity. | `OPEN` |
| RC-B02 | Provenance in the user journey | `ProductionAgentOrchestrator`, `ArtifactManager`, Library | CI instrumentation confirms the reference local operation creates/links an owned artifact after runtime reconstruction; inspect evidence through the product UI on device. | `PARTIAL` / `CI_VERIFIED` |
| RC-B03 | Approval and exact-step continuation | `TrustCenter`, typed continuation runtime, durable task product kernel | CI instrumentation confirms an approved local continuation resumes once and a denied continuation does not apply or resume after runtime reconstruction; UI-driven replay proof remains. | `PARTIAL` / `CI_VERIFIED` |
| RC-B04 | Secret ownership enforcement | `SecretVault`, active workspace, owned consumer adapter | A/B capability denial/consumption runtime scenario without raw secret exposure. | `OPEN` |
| RC-B05 | Project isolation end-to-end | Project file/knowledge/memory/vault/artifact managers | Execute documented A/B fixture on Android and preserve sanitized evidence. | `OPEN` |
| RC-B06 | Full core user journey | Project, Chat, Tasks, Trust, Library, recovery UI | The local file/approval/recovery segment is CI-verified; the full UI journey and device evidence remain. | `OPEN` |
| RC-B07 | Android runtime verification | Device/API matrix | Real device/API26+ evidence for permissions, storage, WorkManager, UI, accessibility baseline, and process death. | `BLOCKED / REAL_DEVICE_ACCESS_REQUIRED` |
| RC-B08 | Security/privacy final verification | Privacy/permission/consent/deletion paths | Final-artifact audit and device confirmation paths. | `OPEN` / `EXTERNAL` |
| RC-B09 | Release build/R8 | Gradle release configuration and CI | `main` run `32805967708` عند `8868656e` نجحت في release compile وR8 وsigned packaging وmapping وSHA-256 وAPI 29 instrumentation/native. | `SIGNED_RELEASE_CANDIDATE_EVIDENCED` |
| RC-B10 | Signed APK/AAB | Release owner and protected signing environment | هوية production جديدة مع recovery مشفرة خارج GitHub؛ `apksigner --verify` و`--print-certs` نجحا للحزمة الموقعة في run `32805967708`، مع fingerprint وhashes محفوظة في سجل التدقيق. | `SIGNED_RELEASE_CANDIDATE_EVIDENCED` / `EXTERNAL_PUBLICATION_GATES_REMAIN` |

## 6. Parallel quality guardrails

Fix a parallel quality issue only when it blocks the acceptance journey, introduces a privacy/security inconsistency, or invalidates required Android accessibility/layout behavior. Do not initiate broad redesigns or new capability families.

| Quality lane | Required release response |
|---|---|
| UI loading, empty, and error states | Repair only states reached by the reference journey; add localized resources for en/ar/es/zh. |
| RTL/LTR, TalkBack, font scale, and touch targets | Validate the reference journey on device; fix observed failures without speculative UI rewrites. |
| Performance and startup | Measure first. Fix only demonstrated release-path regressions, leaks, crashes, or unsafe background behavior. |
| Calendar | Retain current fail-closed typed path. Do not add OAuth, retries, or provider breadth to release scope absent a verified release need. |
| Browser | Retain public, user-confirmed handoff. Full DOM/authenticated automation is post-release. |

## 7. Evidence and milestone log

Every milestone entry must include the fields below. A source edit without a passing appropriate test is not a completed milestone, and a CI run does not substitute for a device or external result.

| Date / milestone | Current state | Completed | Blockers | Next action | Files changed | Tests / evidence | Commit | Remaining release risk |
|---|---|---|---|---|---|---|---|---|
| 2026-08-24 — Local device-data erase evidence | Local device-data erase is implemented as a separate confirmed local action. | Audit register and closure status record that it stops AIRI-owned work, wipes owned local stores, and locally signs out without remote/Firebase deletion. | Physical cancel/confirm/wipe and remote-account-retained proof; model downloads/provider/cloud data are outside the action. | Keep it in device matrix; do not broaden deletion claims. | `DataDeletionCoordinator`, privacy UI/resources, audit/status docs. | Core guard 76/76, strict localization, CI `32690168612` and `32692865948`. | `87797631`, `aea732d3`, `d96c6ce8` | No device proof, signed artifact, or store/legal evidence. |
| 2026-08-24 — Local approval/recovery journey | The reference local file path now has Android integration coverage across task ownership, approval/denial, runtime recreation, one apply, provenance, duplicate-resume refusal, denied no-apply/no-resume behavior, and explicit owner selection when a project has concurrent eligible tasks. | `ProjectFileApprovalRecoveryTest` creates a managed project file and running owned task, persists a private proposal and decision, recreates the durable/workspace/file/artifact/proposal runtimes, verifies the approved branch applies and links project/task/run/step evidence exactly once, and verifies the denied branch creates no artifact, changes no file bytes, and cannot resume. `ProjectFileEditTaskSelector` exposes only a unique owner automatically; Library requires the user to choose an eligible task when more than one could own the edit, and JVM tests prove it cannot take first-record ordering as authority. | Physical-device UI approval/denial, deliberate process kill, TalkBack, and full Chat→Tasks→Trust→Library traversal remain. | Extend only the defined journey; do not introduce provider or browser scope. | `ProjectFileApprovalRecoveryTest.kt`, `ProjectFileEditTaskSelector.kt`, `ProjectFileEditTaskSelectorTest.kt`, Library resources. | Core guard 76/76, strict localization, Android CI `32695492715`, `32697399760`, and `32702865271`: debug/unit/lint/release-source, API 29 instrumentation, and native verification passed. | `73ac96ad`, `bfef9bb3`, `6d78e0d0` | This is CI emulator evidence, not signed artifact, physical-device, provider, or store proof. |
| 2026-08-24 — Signed release evidence gate | The Feature Freeze audit added an evidence contract for signer verification, certificate output, mapping presence, and checksums as one protected CI set. | `ca881a1b` fixed runner discovery of `apksigner`; `main` run `32783660291` passed signed packaging, `apksigner verify --verbose`, `--print-certs`, mapping, SHA-256 evidence, API 29 instrumentation, and native verification. | Physical device, provider, and store/legal gates remain. | Preserve this signed evidence and execute the device matrix; do not re-sign or expand scope. | `.github/workflows/android_build.yml`, `tools/verify_core_changes.py`, `docs/deployment/BUILD_AND_RELEASE.md`. | Core guard 80/80, strict localization, Android CI `32783660291` successful. | `fa0e62da`, `ca881a1b` | CI configuration plus verified output is signed evidence, not a public-release approval. |
| 2026-08-24 — Unsigned release package evidence | The release gate previously compiled sources but did not prove R8 package creation on `cp-foundation`. | Non-`main` CI now builds release APK/AAB with R8, verifies `mapping.txt`, APK badging, AAB compressed-data integrity, and `UNSIGNED_SHA256SUMS`; run `32720458806` succeeded end-to-end through API 29 instrumentation and native verification. Downloaded evidence contains `app-release-unsigned.apk` SHA-256 `20c6fffb578feee017d4ef25b0eda9944863f365bc8613f6747969e0bf77a236`, `app-release.aab` SHA-256 `647166abd6c955c7f68ab2f528b0a85b998776f79117c9b79c1ea81227569dcf`, and `mapping.txt` SHA-256 `d82ae1096fcbdaaf493952997b2a32bd3cf8ab7acfabc085c5c4ca9a0aced5a7`. APK badging reports applicationId `com.airi.assistant`, versionCode `1`, minSdk `26`, targetSdk `36`, arm64-v8a, and no Billing permission. | This historical structure evidence does not replace the signed artifact, device install, provider, or publisher gates. | Preserve it as historical package-structure evidence; signed evidence is recorded separately in run `32783660291`. | `.github/workflows/android_build.yml`, build evidence artifact, mapping, checksums. | Core guard 77/77, strict localization, CI `32720458806` success. | `002443a4`, `e4e36d95` | Unsigned artifacts are never install/publish evidence. |
| 2026-08-24 — Feature Freeze static repository audit | Release-only audit of current source, contracts, localization, static security boundary, core-health review signals, and direct dependency inventory. | `tools/security_scan.py` passed its eight security-boundary checks with no secret findings; `airi_core_health.py` found no merge conflict, no localization parity failure, and no unfinished marker. Its four empty callbacks were reviewed as intentional non-release controls: hidden retry token streaming, long-press-only message gestures, and a read-only email field. Four large Kotlin files remain maintainability reviews, not demonstrated frozen-journey failures. `supply_chain_inventory.py` reproduced a 42-entry direct dependency inventory. | Physical device, live providers, store/legal, and final artifact runtime evidence remain; no new internal product feature is admitted. | Continue only publication gates. | Audit scripts and release closure records; no product-source change from the static audit. | Security scan PASS; core-health blocking=0; direct dependency inventory regenerated without tracked change. | `fa0e62da` worktree audit | Static audit cannot replace runtime or publisher evidence; signed CI evidence is recorded separately. |
| 2026-08-24 — Release signing identity forensics and closure | Safe search found no tracked/untracked historical release key material or prior release identity; public `v0.1.0-alpha/airi-debug.apk` carried Android Debug only. Following owner approval, a new production identity and owner-controlled encrypted recovery were created outside GitHub; local secret material was removed after verification. | `ca881a1b` resolved build-tools `apksigner` discovery and `main` run `32783660291` verified the resulting signed artifact. No secret value was read or printed. | Signed evidence is complete for this artifact; do not rotate or replace the identity without a fresh controlled recovery/evidence process. | Keep `SIGNED_RELEASE_CANDIDATE_EVIDENCED`; execute device/provider/Play/legal gates only. | Audit register, publication handoff, root README, closure status, this ledger. | `git`/Actions metadata inspection, public APK certificate inspection, encrypted recovery verification, protected CI evidence. | `ca881a1b` signing closure | The absence finding does not prove Play Console ownership or grant permission to publish; signed artifact evidence does not replace external gates. |
| 2026-08-24 — Commercial surface release boundary | The static audit found reachable Paywall, Stripe payment, billing history, marketplace, and community routes while the frozen release has no payment/provider/store evidence. | `ReleaseScopePolicy` keeps commercial surfaces disabled; stale or restored routes show a localized return-safe screen rather than initializing Billing, Stripe, or marketplace dependencies. Settings no longer exposes those entry points, and `tools:node="remove"` removes the merged Billing permission from the release manifest. JVM policy test and core guard pin the boundary. | Device proof of the changed route and final merged-manifest inspection remain; commercial/provider/store capability is deliberately excluded, not certified. | Keep the surface disabled until a distinct post-release commercial scope has real provider, legal, signer, and Play evidence. | Release scope policy/test, root routes, Settings, manifest, four locale resources, core guard. | Core guard 77/77, strict localization, Android CI `32716171238`: debug/unit/lint/release-source, API 29 instrumentation, and native verification passed. | `8b23ae2e`, `b1ef8b6c` | This removes an undeclared commercial dependency from the frozen release journey; it is not payment-provider verification. |
| 2026-08-24 — Execution-integrity closure audit | تدقيق مركز لمسارات orchestrator/scheduler/tool result كشف ثلاث حالات قابلة للنجاح الشكلي: cycle/خطة ناقصة، payload صيانة `system` غير معروف، وفشل ToolExecutor يعامل كنجاح. | `AgentTeamPolicy` يرفض cycle، وorchestrator لا ينجح قبل اكتمال كل task؛ payload النظام محصور في الصيانة المسماة وتفشل worker الإدخال غير المعروف؛ و`SkillService` يبث/يسجل `result.success` ويعيد `Failed` عند فشل الأداة. كل إصلاح له اختبار وحارس. | لا مزود حي ولا Doze/OEM أو جهاز؛ ToolExecutor/provider review الأوسع يبقى خارج claim الإصدار. | لا توسعة للمسارات؛ نفذ فقط matrix والجهاز والمزود والمتجر والقانون. | policy/orchestrator/scheduler/SkillService/tests/core verifier/READMEs. | CI `32761300619` على `ec359054` أثبتت الإصلاحات، ثم run `32783660291` عند `ca881a1b` أثبتت signed APK/AAB و`apksigner` وAPI 29 instrumentation/native. | `3a838e44`, `47d85b9a`, `7439a9a7`, `ec359054`, `ca881a1b` | المرشح رُقّي fast-forward إلى `main`، وتوجد signed APK/AAB و`apksigner` evidence؛ لا تزال الحواجز الخارجية قائمة. |

## 8. Next action

**Execute publication gates in the order and with the evidence contract in `RELEASE_PUBLICATION_HANDOFF.md`, without new product work:** (1) نفذ مصفوفة API/ABI على أجهزة حقيقية، بما فيها رحلة project-file وlocal erase؛ (2) اجمع live provider consent/cancel/revoke فقط للمزودين الذين سيعلنهم الإصدار؛ و(3) أكمل publisher-owned privacy وData safety والقانون وPlay pre-launch/listing/rollout. سجل كل نتيجة في `RELEASE_DEVICE_AND_STORE_MATRIX.md`; a missing external result remains a gate, not a defect to solve by expanding `cp-foundation`.

## 9. External release gates

| Gate | Required evidence | Why this cannot be inferred from the repository |
|---|---|---|
| Real Android device matrix | API/ABI/device results, permissions, file picker, WorkManager/Doze, process-death/reopen, RTL/LTR, TalkBack, local erase. | Hardware, Android Settings, OEM behavior, and visual/accessibility runtime. |
| Release artifact | `CI_VERIFIED`: signed APK/AAB, R8 mapping, native output, SHA-256, and `apksigner` evidence from run `32783660291`. | Does not infer device runtime or publisher acceptance. |
| Controlled signing | `CI_VERIFIED`: protected signing material produced signed APK/AAB and certificate evidence; private material remains outside source control. | Does not grant Play Console, legal, or rollout authority. |
| Provider evidence | Consent, cancellation, revocation, recovery, and error behavior for every declared production provider. | Requires real credentials and third-party runtime. |
| Store and legal | Play pre-launch, Data safety, privacy policy, permission declarations, model/provider terms, package/version policy. | Requires publisher account, final artifact, and legal/policy review. |

## 10. Change control

This document governs release execution. [`AIRI_PRODUCT_GAP_MATRIX.md`](AIRI_PRODUCT_GAP_MATRIX.md) remains strategy and long-term product scope; it must not be used to pull a new product family into this closure program. [`AIRI_FINAL_CLOSURE_MAP.md`](AIRI_FINAL_CLOSURE_MAP.md) remains the detailed technical trace. When the two differ, this document controls release priority while the technical map records implementation detail.
