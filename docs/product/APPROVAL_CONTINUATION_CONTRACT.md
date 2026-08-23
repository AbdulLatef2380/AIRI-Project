# Approval Continuation Contract

**Status:** `IMPLEMENTED` / `TESTED` / `BUILD_VERIFIED` for the task-scoped GitHub mutation route and the **project-scoped AgentLoop calendar-create** route. Broader agent-tool integration remains `PARTIAL`; Calendar provider/device behavior is `RUNTIME_VERIFICATION_PENDING`.

## Purpose

AIRI must never convert a consent request into a generic retry. An approval decision authorizes **one exact side effect** owned by one durable task, mission, project, run, and plan step. The side effect is persisted before it is invoked, claimed atomically once, and then executed in a fresh safe coroutine scope.

| Element | Contract |
|---|---|
| Durable owner | `DurableTask` owns `TaskApproval` and `ApprovalContinuation`; `MissionKernel` normalizes and validates task/mission/project/run/step alignment at save and load. |
| Pause | `DurableTaskManager.pauseForApproval` moves the task, current run, and current step to `PAUSED` only when the approval and continuation match the live run/step. |
| Approval decision | `PermissionGovernanceLayer` persists `APPROVED`, `DENIED`, or `EXPIRED`. Denied/expired records reject their pending continuation and cannot be claimed. |
| Claim | `claimApprovedContinuation` atomically persists `CLAIMED` **before** a connector call or local typed provider insert. A second click, late callback, restart race, or retry receives no continuation. |
| Connector authorization | A resumed connector request carries `projectId`, `taskId`, `missionId`, `runId`, `stepId`, `idempotencyKey`, and `continuationId`. `GitHubConnector` revalidates the claimed record before making its API call. |
| Local provider authorization | `CalendarCreateRuntime` accepts only `ResumableCalendarCreate`: proposal ID, title hash, schedule hash, fixed calendar policy, and idempotency key. It revalidates claimed task/mission/project/run/step plus private-proposal integrity before one `CalendarTool` call. |
| Retry behavior | `ConnectorRuntimeManager` returns `ApprovalRequired` without fallback or retry. A claimed external mutation executes with `maxRetries = 0`; ambiguous network failure is recorded, never silently replayed. |
| Recovery | Startup scans only continuations that were already approved, unexpired, pending, and task-paused. Connector, project-file, and calendar runtimes select only their mutually exclusive descriptor type; each claims before dispatch, so recovery is also one-shot. Claimed calendar records never replay. |
| Secret and payload boundary | The persisted invocation excludes binary payloads and credentials. Inputs resembling authorization, bearer credentials, API keys, passwords, secrets, or tokens are rejected before continuation persistence. Calendar title, start time, duration, prompt history, and provider response remain private proposal bytes and are absent from durable JSON, provenance, and diagnostics. |

## Live routes

### GitHub connector mutation

1. A task-scoped GitHub mutation reaches `GitHubConnector` with a complete `ConnectorExecutionContext`.
2. `GitHubConnector` persists a task approval and safe `ResumableConnectorInvocation`, then calls `pauseForApproval` **before** obtaining or using a GitHub credential.
3. Trust Center exposes the durable approval. **Allow** persists `APPROVED` and triggers `ApprovalContinuationRuntime.resume`; **Deny** only records denial.
4. The runtime claims the continuation, reconstructs a non-secret `ConnectorInput`, and invokes the connector once with the continuation ID.
5. The connector validates the claimed ownership/idempotency record before the API request. The runtime persists outcome and marks the exact step complete or failed.

> `PROJECT` grant scope is retained as a recorded user decision but does not bypass the exact continuation claim. AIRI does not claim project-wide replay authorization.

### Project-scoped AgentLoop calendar create

1. `AgentLoopTaskRuntime` creates a real task, mission, run, and `calendar_create` step only after the loop requests this typed tool while an active workspace project exists.
2. `CalendarCreateRuntime` validates and stores the event payload privately, derives hashes, writes `ResumableCalendarCreate`, creates `TaskApproval`, and pauses the exact step.
3. Trust Center reads the private proposal only for a dedicated localized review dialog. **Allow** approves and routes only the calendar descriptor; **Deny** or expiry removes the private payload.
4. The calendar runtime claims once, revalidates ownership and hashes, makes one provider insert attempt, creates generic project-owned evidence, and completes or fails the exact task. Generic `ToolDispatcher` calendar insertion is rejected.
5. Startup resumes only approved/unclaimed calendar records. An ambiguous or failed provider result is never retried automatically and requires a fresh user-initiated proposal.

## Evidence

| Evidence | Result |
|---|---|
| `DurableTaskProductKernelTest` | Verifies PAUSED state, approval, one-time claim, task/run/step transition, project mismatch rejection, and secret-like payload rejection. |
| `ConnectorRuntimeManagerTest` | Verifies `ApprovalRequired` is returned without connector retry. |
| `DurableTaskProductKernelTest` | Verifies a calendar descriptor pauses/claims once and hybrid connector/file/calendar descriptors fail closed. |
| `AgentLoopExecutionContextTest` and `AgentLoopSideEffectPolicyTest` | Verify stable task coordinates and that only calendar create, not notes/alarm/accessibility/app actions, can enter the typed branch. |
| `MissionKernel` | Rejects continuation records whose approval/task/mission/project/run/step or safe invocation does not match. |
| Build and source guard | `:app:compileDebugKotlin` and the calendar source-invariant guard passed; Android provider runtime is not implied. |

## Remaining closure work

The generic AgentLoop `ask_confirmation` text sentinel, skill execution, terminal commands, notes, alarms, accessibility actions, browser actions, and other connector mutations do not yet emit their own structured continuation descriptor. Their text or policy pauses must be migrated before the product-wide P0 approval-resume row can be upgraded from `PARTIAL`. Calendar permission/provider success/failure, review dismissal/denial cleanup, process recreation, and duplicate-insert behavior plus physical-device recovery and a credentialed GitHub API mutation remain `RUNTIME_VERIFICATION_PENDING`.
