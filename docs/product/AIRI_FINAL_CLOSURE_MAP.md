# AIRI Final Closure Map

**Branch:** `cp-foundation`  
**Purpose:** This is the single execution map for the final closure pass. A status is not upgraded by the existence of a class, screen, or unit test alone. Each row must have a live ownership path, relevant persistence and security boundary, an appropriate test, and acceptance evidence.

## Status vocabulary

| Status | Meaning |
|---|---|
| `IMPLEMENTED` | A source path is wired to a live runtime. |
| `TESTED` | A targeted automated test or deterministic verifier passed. |
| `BUILD_VERIFIED` | Relevant compile/lint/package gate passed for the current revision. |
| `RUNTIME_VERIFICATION_PENDING` | A real device, provider credential, or external service is required. |
| `EXTERNAL_VERIFICATION_REQUIRED` | The remaining gate cannot be executed from this repository. |
| `PARTIAL` | Some live path exists, but a stated product invariant is still open. |

## P0 closure map

| ID | Capability / owner | Current state | Runtime and persistence | Security / scope | UI and recovery | Tests / acceptance gate | External dependency |
|---|---|---|---|---|---|---|---|
| P0-01 | Mission Kernel / `DurableTaskManager`, `MissionKernel` | `IMPLEMENTED` / `TESTED` baseline | Durable task, Mission identity, run, step, approval and timeline normalize together across JSON load/write. | MissionKernel rejects cross-project run/approval ownership and resource access outside the task project. | Agent Tasks and plan dashboard expose task state; process-death restoration remains incomplete. | `MissionKernelTest`, `DurableTaskProductKernelTest`, core verifier. | Device process-death verification. |
| P0-02 | Project Context / `RagRetriever`, `ProjectContextResolver` | `IMPLEMENTED` + `TESTED` | Active-project metadata, file and artifact references join scoped memory/knowledge on the live RAG path. | Admission rejects non-matching project candidates; no raw URI/path/hash/secret enters the reference block. | Chat uses active workspace project; visual device proof remains pending. | `ProjectContextAdmissionPolicyTest`, core verifier. | Android device/model run. |
| P0-03 | Project isolation / project files, knowledge, memory, artifacts, vault | `PARTIAL` | Files, knowledge and project memory are scoped; artifact session metadata is scoped. | Secret capabilities are not project-owned yet; cross-resource kernel tests are incomplete. | No unified isolation evidence screen. | Add cross-project file/memory/knowledge/artifact/secret integration tests. | Device regression only after local gates. |
| P0-04 | Approval → exact step resume / governance + durable execution | `PARTIAL` | Approval records and Trust Center are live; continuation state is not yet an exact-step resume contract. | Decision is policy-gated and durable; late callback and duplicate-tool barriers need direct proof. | Trust Center shows task/run/step and decision controls. | Add paused-step, expiry, restart, and no-duplicate-call tests. | Device recovery validation. |
| P0-05 | Project secrets / `SecretVault` | `IMPLEMENTED` / `TESTED` broker | Global and project/connector namespaces are Keystore-backed; project capabilities carry project/connector/task metadata. | Agent/operation/project/connector/TTL/use binding is enforced; project capability cannot fall back to global secret. | No project-secret management UI. | `SecretVaultTest` covers project/connector denial and revocation; expiry and one-use baseline remain covered. | Keystore/device coverage and provider adapter context. |
| P0-06 | Memory Fabric / `MemoryManager`, Room, RAG | `PARTIAL` | Scoped/provenanced durable memory and RAG are live. | Scope, privacy and expiry apply; full working/episodic/semantic taxonomy and import/export remain open. | Memory explain/edit/delete hooks exist; complete provenance UX remains open. | Add ranking/decay/dedupe/contradiction and project isolation coverage. | Device migration verification. |
| P0-07 | File Intelligence / `ProjectFileManager`, `ProjectKnowledgeManager` | `PARTIAL` | Import, validation, hash, managed storage, text extraction/index and lexical project knowledge are live. | Project ownership and bounded attachment context are live. | Project file UI/preview exists; broad document/media parser coverage and restore/version closure remain open. | Add lifecycle, media/parser, trash/restore and cross-project tests. | Android picker/media implementation coverage. |
| P0-08 | Artifact provenance / `ArtifactManager`, Room artifacts | `PARTIAL` | Artifact preview/snapshot/session metadata exist. | Artifact is session/project-adjacent, not yet task/run/step-owned. | Preview/restore exists; creation explanation is incomplete. | Add provenance invariant and cross-project lookup tests. | Device file/share/download verification. |
| P0-09 | Cancellation / orchestrator, durable task, scheduler, terminal | `PARTIAL` | Active Work Stop cancels live agent, tasks, user jobs and terminal paths. | Cancellation does not claim connector/browser/remote cancellation without an API. | Agent Tasks confirmation states the bounded scope. | Expand a common cancellation contract and late-callback/recovery tests. | Runtime-specific device/browser checks. |
| P0-10 | Diagnostics and evidence / audit, privacy guard | `PARTIAL` | Task/run/step timelines and redacted diagnostics exist. | Cloud history/device identifiers and provider bodies are redacted. | Developer and task surfaces are partial. | Add evidence bundle and full trace traversal tests. | Provider/device traces. |

## P1 closure map

| ID | Capability / owner | Current state | Runtime and persistence | Security / scope | UI and recovery | Tests / acceptance gate | External dependency |
|---|---|---|---|---|---|---|---|
| P1-01 | Workspace file/code editor | `MISSING` | Project files exist; no live editable proposal→diff→approval→apply runtime. | Must run in project sandbox with approved write boundary. | Workspace has file surfaces, not a full editor. | Proposal, diff, rollback, large-file and read-only tests. | Device usability validation. |
| P1-02 | Terminal 2.0 | `PARTIAL` | Android sandbox terminal has bounded output, timeout and cancellation. | argv-only and governance-bound; desktop PTY is absent. | Terminal screen exposes output/history. | Session/cwd/stdin/resource/audit integration tests. | Android/desktop process execution. |
| P1-03 | Browser Agent | `PARTIAL` | Public URL read/search controls exist; no full tab/DOM interaction runtime. | Navigation policy and takeover boundary exist. | Browser controls are limited. | Simulated browser state/policy/approval contract before real runtime. | Browser session, login, CAPTCHA and purchase flows. |
| P1-04 | Execution Center 2.0 | `PARTIAL` | Durable timeline/replay baseline exists. | Approval and cancellation paths are bounded. | Task/plan/trust screens are separate partial views. | Unified replay/recovery/artifact/evidence tests. | Device visual verification. |
| P1-05 | Research mode | `PARTIAL` | Research evidence policy and source boundary exist. | External data remains untrusted and cited. | Research UI/evidence linkage incomplete. | Cross-check, contradiction and citation artifact tests. | External source availability. |
| P1-06 | Automation 2.0 | `PARTIAL` | Scheduled jobs create durable tasks with project/owner/privacy. | User jobs can be stopped without stopping maintenance jobs. | Run-now/pause/retry/replay flow is incomplete. | Scheduler transition/recovery/notification tests. | Android Doze/OEM and external triggers. |
| P1-07 | Model control plane | `PARTIAL` | Routing policy and decision reasons are live. | Privacy/network/budget gates exist. | Decision explanation projection is incomplete. | Decision reason/budget/local-cloud tests. | Provider/local model hardware. |
| P1-08 | Device Mesh + continuity | `PARTIAL` | Opt-in redacted state sync and continuation snapshots exist. | Secrets/device handles/processes are excluded. | Resume-on-device UX and paired trust are incomplete. | Snapshot merge/device ownership tests. | Two devices/Firebase credentials. |
| P1-09 | Desktop local model runtime | `MISSING` | Android local path exists; desktop runtime is not live. | Must not expose arbitrary local paths or pretend model support. | Desktop product shell requires separate delivery. | GGUF compatibility/load/cancel fixtures. | Desktop OS/llama.cpp/hardware. |
| P1-10 | Voice / vision / media | `PARTIAL` | Voice state guard and local/media foundations exist. | Consent and privacy boundaries exist; advanced video pipeline absent. | Voice UI is partial. | State/interruption/Arabic-English/media pipeline tests. | Microphone/Bluetooth/model providers. |
| P1-11 | Skills/MCP/connectors | `PARTIAL` | Trust policy, skill runtime and connector registry exist. | Enablement is policy-governed; full simulation/capability registry closure is open. | Skills/integrations UI exists. | Dependency/risk/health/simulation tests. | OAuth/MCP endpoints. |

## P2 closure map

| ID | Capability / owner | Current state | Closure gate |
|---|---|---|---|
| P2-01 | Canvas | `MISSING` | Implement only as editable artifact runtime after P0/P1 ownership and evidence are closed. |
| P2-02 | Developer Center / Git / Database | `PARTIAL` | Consolidate shared task/project state; retain read-only database and approval-bound Git mutations. |
| P2-03 | Security and Privacy Center | `PARTIAL` | Unify observable read/write/execute/memory/connector surface with export, revoke and audit evidence. |
| P2-04 | Update, backup and recovery | `PARTIAL` | Add signed metadata/rollback/migration and backup-restore evidence without claiming store release. |
| P2-05 | UX/UI product pass | `PARTIAL` | Close runtime first; then verify mobile/RTL/large text/TalkBack/error/loading and desktop navigation where a runtime exists. |

## Mandatory final gates

| Gate | Required evidence | Current blocker class |
|---|---|---|
| Source integrity | `git diff --check`, architecture/security/core verifiers | Internal. |
| Android build | `compileDebug`, `assembleDebug`, `lintDebug`, release build/R8 where configured | Internal environment capacity; resolve or record exact failure. |
| Tests | Unit, policy, Room/migration, security, integration, UI/instrumentation as applicable | Internal for code; device/emulator for instrumentation. |
| Supply chain | Dependency inventory/SBOM, vulnerability/license review, secret scan | Internal unless vendor registry is unavailable. |
| Runtime proof | Project isolation, approval resume, cancellation, file/media, voice, continuity | Physical device, real credentials, two-device/provider cases where applicable. |
| Release proof | Manifest, hashes, test/security reports, known external verification list | Signing keys and store/provider accounts are external. |

## Milestone status log

| Milestone | Status | Evidence |
|---|---|---|
| Project Context admission | `IMPLEMENTED` / `TESTED` | `PRODUCT_KERNEL_CONTEXT_CONTRACT.md`, `ProjectContextAdmissionPolicyTest`, core verifier. |
| Trust Center live decisions | `IMPLEMENTED` / `TESTED` | `TRUST_CENTER_CONTRACT.md`, `AgentTasksScreen`, core verifier. |
| Final closure pass | `IN_PROGRESS` | This map and `AIRI_FINAL_CLOSURE_STATUS.md` are updated only at meaningful closure milestones. |
