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
| P0-03 | Project isolation / project files, knowledge, memory, artifacts, vault | `PARTIAL` | Files, knowledge, project memory, project/connector secret capabilities, and artifact project provenance are scoped. | Project capabilities reject connector/project mismatch and no global fallback occurs; artifact writes validate project/task/run/step and reads are project-scoped. | No unified isolation evidence screen. | `ProjectResourceIsolationTest` compiles a real file→knowledge→memory→project-secret capability→artifact A/B fixture. | Device regression and instrumentation execution remain pending. |
| P0-04 | Approval → exact step resume / governance + durable execution | `PARTIAL` | Task-scoped GitHub mutations persist a safe continuation, pause the exact run/step, claim once before dispatch, and recover pre-approved records after restart. | Mission/project/run/step/idempotency ownership and claimed-state connector authorization reject stale or duplicate calls; AgentLoop/skills/terminal and other connector routes are not migrated yet. | Trust Center Allow triggers the continuation runtime only after durable approval; Deny/expiry never dispatch. | `DurableTaskProductKernelTest`, `ConnectorRuntimeManagerTest`, `APPROVAL_CONTINUATION_CONTRACT.md`; add expiry/recovery and all-tool-route coverage. | Credentialed GitHub and device process-recovery validation. |
| P0-05 | Project secrets / `SecretVault` | `IMPLEMENTED` / `TESTED` / `BUILD_VERIFIED` GitHub path | Global and project/connector namespaces are Keystore-backed; project capabilities carry project/connector/task metadata. `SecretManagerScreen` binds `GITHUB_PAT` save/revoke to the active workspace and shows presence only; `GitHubConnector` validates durable task/run/step ownership and consumes it internally through a one-use project/connector capability. | Agent/operation/project/connector/TTL/use binding is enforced; project capability cannot fall back to global secret, including GitHub execution with project context. | Project management UI is active for GitHub credential only; no raw preview/copy is exposed there. | `SecretVaultTest`, `DurableTaskProductKernelTest`, core verifier, Android UI compilation; add adapter-level HTTP fixture. | Keystore/workspace-switch device coverage, adapter HTTP fixture, and provider/connector migration. |
| P0-06 | Memory Fabric / `MemoryManager`, Room, RAG | `PARTIAL` / `BUILD_VERIFIED` management path | Scoped/provenanced durable memory and RAG are live. | Scope, privacy and expiry apply; full working/episodic/semantic taxonomy and import/export remain open. | MemoryScreen explains provenance and now confirms/deletes one durable memory through ViewModel with projection/count refresh; editing and complete provenance UX remain open. | Memory policy tests, core verifier, Android UI compilation; add ranking/decay/dedupe/contradiction and device deletion coverage. | Device migration, deletion, accessibility, and process-recreation verification. |
| P0-07 | File Intelligence / `ProjectFileManager`, `ProjectKnowledgeManager` | `IMPLEMENTED` / `BUILD_VERIFIED` lifecycle baseline | Import, validation, hash, managed storage, text extraction/index, lexical project knowledge, project-owned trash/restore/purge, and account wipe wiring are live. | Delete archives before active removal, clears that file's knowledge index, excludes deleted records from project reads/context, and restores only through managed storage with explicit re-index. | Library exposes active files, recently deleted files, restore, and permanent delete; rich parsers and content version history remain open. | `ProjectResourceIsolationTest` compiles file→knowledge→delete→restore→reindex plus project isolation; add parser/version tests. | Android picker/media and instrumentation execution. |
| P0-08 | Artifact provenance / `ArtifactManager`, Room artifacts | `IMPLEMENTED` / `TESTED` / `BUILD_VERIFIED` baseline | Room v9 stores project/task/run/step/tool/model metadata, summary, and content hash; successful orchestrator steps create and link private result artifacts. | Writes require matching active task project/run/step; read APIs and Project Context are project-scoped; legacy rows normalize project=session. | Library exposes bounded task/run/step/tool/model/summary evidence and a 12-character integrity prefix; broader producer provenance remains partial. | `ArtifactProvenanceTest`, `DurableTaskProductKernelTest`, v9 migration harness, core verifier, Android UI compilation. | Device migration, evidence accessibility, preview/share/download, and multi-step provider run. |
| P0-09 | Cancellation / orchestrator, durable task, scheduler, terminal | `PARTIAL` | Active Work Stop cancels live agent, tasks, user jobs and terminal paths. | Cancellation does not claim connector/browser/remote cancellation without an API. | Agent Tasks confirmation states the bounded scope. | Expand a common cancellation contract and late-callback/recovery tests. | Runtime-specific device/browser checks. |
| P0-10 | Diagnostics and evidence / audit, privacy guard | `PARTIAL` | Task/run/step timelines and redacted diagnostics exist. | Cloud history/device identifiers and provider bodies are redacted. | Developer and task surfaces are partial. | Add evidence bundle and full trace traversal tests. | Provider/device traces. |

## P1 closure map

| ID | Capability / owner | Current state | Runtime and persistence | Security / scope | UI and recovery | Tests / acceptance gate | External dependency |
|---|---|---|---|---|---|---|---|
| P1-01 | Workspace file/code editor | `PARTIAL` | `ProjectFileEditRuntime` persists a private text proposal, diff summary and hash-only typed continuation; `ProjectFileManager` performs project-scoped backup/temp-replace/recovery and clears stale knowledge. A claimed approval applies once and creates/links bounded execution evidence. | Active workspace, project file, durable task/mission/run/step, source hash, candidate hash and typed continuation must match; candidate text/paths never enter task JSON or artifact provenance. | Library exposes a user-authored replacement text and bounded diff review; it requests approval through Trust Center and never applies directly. | JVM kernel/policy tests, Android revision fixture compilation, core verifier; still need actual instrumentation run plus storage-failure, accessibility, large-file, and process-recreation device verification. | Android device usability and storage failure verification. |
| P1-02 | Terminal 2.0 | `PARTIAL` | Android sandbox terminal has bounded output, timeout and cancellation. | argv-only and governance-bound; desktop PTY is absent. | Terminal screen exposes output/history. | Session/cwd/stdin/resource/audit integration tests. | Android/desktop process execution. |
| P1-03 | Browser Agent | `PARTIAL` | Public URL read/search controls exist; `LocalBrowserOperator` proposes a public HTTP(S) handoff and never launches an external activity autonomously; no full tab/DOM interaction runtime. | Shared navigation policy rejects private/unsafe targets; `BrowserUserTakeoverCoordinator` admits one public request, and `AiriApp` revalidates it before platform open only after a visible user choice. | Browser controls remain limited to public external handoff; `geo:` remains proposal-only and there is no authenticated browser state or DOM control. | `BrowserNavigationPolicyTest`, `LocalBrowserOperatorPolicyTest`, `BrowserUserTakeoverCoordinatorTest`, and policy guard pass; add device UI verification and a separate durable approval contract before any side-effecting browser runtime. | Device handoff UX, browser session, login, CAPTCHA and purchase flows. |
| P1-04 | Execution Center 2.0 | `PARTIAL` | Durable timeline/replay baseline exists. | Approval and cancellation paths are bounded. | Task/plan/trust screens are separate partial views. | Unified replay/recovery/artifact/evidence tests. | Device visual verification. |
| P1-05 | Research mode | `PARTIAL` | Research evidence policy and source boundary exist. | External data remains untrusted and cited. | Research UI/evidence linkage incomplete. | Cross-check, contradiction and citation artifact tests. | External source availability. |
| P1-06 | Automation 2.0 | `PARTIAL` | Scheduled jobs create durable tasks with project/owner/privacy and persist last outcome/task evidence. | User jobs can be stopped without stopping maintenance jobs. | Agent Tasks retains completed/failed jobs, supports explicit refresh without polling, and offers a user action to open Execution Center when a durable task is linked; run-now/pause/retry and exact-task focus remain incomplete. | Scheduler transition/recovery/notification tests and UI boundary guard. | Android Doze/OEM and external triggers. |
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
| Exact-step GitHub continuation | `IMPLEMENTED` / `TESTED` / `BUILD_VERIFIED` | `APPROVAL_CONTINUATION_CONTRACT.md`, durable pause/claim runtime, Trust Center resume, targeted Product Kernel and ConnectorRuntimeManager tests. |
| AgentLoop side-effect fail-closed boundary | `IMPLEMENTED` / `TESTED` | `AGENT_LOOP_DURABLE_APPROVAL_CONTRACT.md`; chat-owned loop has no task/run/step and therefore blocks calendar/note/alarm/accessibility/app-intent actions before dispatcher I/O. `AgentLoopSideEffectPolicyTest` and the core guard pass. This is a safety boundary, not a calendar continuation or a replacement for a task-owned AgentLoop runtime. |
| Artifact execution provenance | `IMPLEMENTED` / `TESTED` / `BUILD_VERIFIED` | `ARTIFACT_PROVENANCE_CONTRACT.md`, Room v9, orchestrator result artifact route, targeted tests and core verifier. |
| Final closure pass | `IN_PROGRESS` | This map and `AIRI_FINAL_CLOSURE_STATUS.md` are updated only at meaningful closure milestones. |
